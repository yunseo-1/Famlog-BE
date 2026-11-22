package com.famlog.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;

import com.famlog.candidate.service.ExtractResultHandler;
import com.famlog.candidate.service.StaleAiJobService;
import com.famlog.media.service.MediaProperties;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.GroupApi;

/**
 * 안정성 (S20-5-2): 결과 스트림의 멈춘 대기 메시지 회수(다시 처리, 3번이면 내용 없이 DLQ), 멈춘 AI 요청 정리(늦은 결과는
 * 토큰 수만), 관리자 운영 배너
 */
class ReliabilityTest extends ChatIntegrationTest {

    private static final String GROUP = "test-api";

    @Autowired
    PendingReclaimer reclaimer;

    @Autowired
    StaleAiJobService staleJobService;

    @Autowired
    ExtractResultHandler resultHandler;

    @Autowired
    MediaProperties mediaProperties;

    @Autowired
    List<ReclaimableStream> streams;

    @Test
    void 처리_중_죽은_서버가_남긴_결과는_다시_처리하고_확인한_뒤_지운다() {
        String stream = newStream();
        add(stream, Map.of("jobId", "7", "status", "DONE"));
        readWithoutAck(stream, "dead-server");
        List<Map<String, String>> handled = new ArrayList<>();

        assertThat(reclaimer.reclaim(stream, GROUP, "api-1", "jobId", true, handled::add)).isOne();

        assertThat(handled).containsExactly(Map.of("jobId", "7", "status", "DONE"));
        assertThat(redis.opsForStream().pending(stream, GROUP).getTotalPendingMessages()).isZero();
        assertThat(redis.opsForStream().range(stream, Range.unbounded())).isEmpty();
    }

    @Test
    void 다시_처리하다_실패하면_대기_목록에_남고_세_번_전달되면_내용_없이_DLQ로_옮긴다() {
        String stream = newStream();
        add(stream, Map.of("jobId", "8", "status", "DONE", "candidates", "[{\"title\":\"할머니 댁\"}]"));
        readWithoutAck(stream, "dead-server");

        assertThat(reclaimer.reclaim(stream, GROUP, "api-1", "jobId", true, fields -> {
            throw new IllegalStateException("DB down");
        })).isZero();
        assertThat(redis.opsForStream().pending(stream, GROUP).getTotalPendingMessages()).isOne();

        // 두 번째 시도도 실패 → 전달 3번째에는 다시 처리하지 않는다
        reclaimer.reclaim(stream, GROUP, "api-1", "jobId", true, fields -> {
            throw new IllegalStateException("DB down");
        });
        List<Map<String, String>> handled = new ArrayList<>();
        assertThat(reclaimer.reclaim(stream, GROUP, "api-1", "jobId", true, handled::add)).isOne();

        assertThat(handled).isEmpty();
        assertThat(redis.opsForStream().pending(stream, GROUP).getTotalPendingMessages()).isZero();
        List<MapRecord<String, Object, Object>> dead = redis.opsForStream().range(PendingReclaimer.dlqOf(stream),
                Range.unbounded());
        assertThat(dead).hasSize(1);
        assertThat(dead.getFirst().getValue()).containsEntry("jobId", "8").containsEntry("error", "MAX_DELIVERIES")
                .doesNotContainKey("candidates");
        assertThat(dead.getFirst().getValue().toString()).doesNotContain("할머니");
    }

    @Test
    void 결과_스트림_소비자는_회수를_부를_수_있다() {
        assertThat(streams).hasSize(2);
        streams.forEach(ReclaimableStream::reclaimPending);
    }

    @Test
    void 결과가_30분_넘게_없는_AI_요청은_STALE로_정리하고_늦은_결과는_토큰_수만_남긴다() throws Exception {
        long mom = createUser();
        long group = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        long room = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class, group);
        long stale = job(group, room, "now() - interval '31 minutes'");
        long fresh = job(group, room, "now() - interval '29 minutes'");

        assertThat(staleJobService.failStale()).isPositive();

        assertThat(jdbcTemplate.queryForMap("SELECT status, error_code FROM ai_jobs WHERE id = ?", stale))
                .containsEntry("status", "FAILED").containsEntry("error_code", "STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_jobs WHERE id = ?", String.class, fresh))
                .isEqualTo("QUEUED");

        resultHandler.apply(Map.of("jobId", Long.toString(stale), "status", "DONE", "candidates", "[]",
                "modelVersion", "claude-haiku-5-5", "promptVersion", "extract_v2", "inputTokens", "1200",
                "outputTokens", "80", "cacheReadTokens", "0", "cacheWriteTokens", "0", "latencyMs", "900"));
        assertThat(jdbcTemplate.queryForMap("SELECT status, error_code, input_tokens, output_tokens, candidate_count "
                + "FROM ai_jobs WHERE id = ?", stale))
                .containsEntry("status", "FAILED").containsEntry("error_code", "STALE")
                .containsEntry("input_tokens", 1200).containsEntry("output_tokens", 80);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM registration_candidates WHERE job_id = ?",
                Integer.class, stale)).isZero();
    }

    @Test
    void 관리자_운영_배너는_이번_달_비용과_DLQ_길이를_준다() throws Exception {
        String dlq = mediaProperties.streams().jobs() + ":dlq";
        Long before = redis.opsForStream().size(dlq);
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of("mediaId", "1", "error", "STUCK"))
                .withStreamKey(dlq));

        mockMvc.perform(get("/admin/api/ops-summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/ops-summary").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(matchesPattern("\\d{4}-\\d{2}")))
                .andExpect(jsonPath("$.budgetUsd").value(20))
                .andExpect(jsonPath("$.level").value(oneOf("OK", "WARN", "OVER")))
                .andExpect(jsonPath("$.deadLetters['" + dlq + "']").value((before == null ? 0 : before) + 1));
    }

    private static String newStream() {
        return "test:reclaim:" + UUID.randomUUID();
    }

    private void add(String stream, Map<String, String> fields) {
        redis.opsForStream().createGroup(stream, ReadOffset.from("0"), GROUP);
        redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(stream));
    }

    /** 결과를 읽고 확인하지 않은 채 죽은 서버 */
    private void readWithoutAck(String stream, String consumer) {
        redis.opsForStream().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(10)
                .block(Duration.ofMillis(100)), StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }

    private long job(long group, long room, String createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs,
                    created_at)
                VALUES (?, ?, 0, 1, 'BATCH', 1, '[]'::jsonb, %s) RETURNING id""".formatted(createdAt), Long.class,
                group, room);
    }
}
