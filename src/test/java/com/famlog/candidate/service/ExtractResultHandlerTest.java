package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.famlog.support.Family;
import com.famlog.support.IntegrationTest;

/** 워커 분석 결과 반영 (S16: ai_jobs 상태·측정값만, 후보 저장은 S18). 원문 컬럼은 없다. */
class ExtractResultHandlerTest extends IntegrationTest {

    @Autowired
    ExtractResultHandler handler;

    @Autowired
    StringRedisTemplate redis;

    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        Family family = Family.create(mockMvc, this::createUser);
        long roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        jobId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs)
                VALUES (?, ?, 0, 3, 'RULE', 1, '{"M1": 1}') RETURNING id
                """, Long.class, family.groupId(), roomId);
    }

    @Test
    void 완료_결과는_후보_수와_측정값을_남긴다() {
        handler.apply(result("DONE", "[{\"type\":\"TODO\"},{\"type\":\"PLACE\"}]"));

        Map<String, Object> job = job();
        assertThat(job).containsEntry("status", "DONE").containsEntry("candidate_count", 2)
                .containsEntry("model_version", "fake").containsEntry("prompt_version", "none")
                .containsEntry("input_tokens", 120).containsEntry("output_tokens", 30)
                .containsEntry("latency_ms", 850);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(job.get("error_code")).isNull();
    }

    @Test
    void 실패_결과는_오류_코드를_남긴다() {
        Map<String, String> failed = result("FAILED", "[]");
        failed.put("errorCode", "INVALID_OUTPUT");

        handler.apply(failed);

        assertThat(job()).containsEntry("status", "FAILED").containsEntry("error_code", "INVALID_OUTPUT");
    }

    @Test
    void 후보_목록이_배열이_아니면_실패로_남긴다() {
        handler.apply(result("DONE", "{\"type\":\"TODO\"}"));

        assertThat(job()).containsEntry("status", "FAILED").containsEntry("error_code", "INVALID_RESULT");
    }

    @Test
    void 이미_끝난_요청의_결과가_다시_오면_무시한다() {
        handler.apply(result("DONE", "[]"));
        Map<String, String> late = result("FAILED", "[]");
        late.put("errorCode", "LLM_FAILED");

        handler.apply(late);

        assertThat(job()).containsEntry("status", "DONE").containsEntry("candidate_count", 0);
    }

    @Test
    void 결과_스트림으로_오면_반영하고_스트림에서_지운다() throws Exception {
        redis.opsForStream().add(StreamRecords.newRecord().in("famlog:ai.extract-results")
                .ofMap(result("DONE", "[]")));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!"DONE".equals(job().get("status")) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertThat(job()).containsEntry("status", "DONE");
        Thread.sleep(200);
        assertThat(redis.opsForStream().range("famlog:ai.extract-results",
                org.springframework.data.domain.Range.unbounded()))
                .noneMatch(record -> Long.toString(jobId).equals(record.getValue().get("jobId")));
    }

    private Map<String, String> result(String status, String candidates) {
        Map<String, String> result = new HashMap<>();
        result.put("jobId", Long.toString(jobId));
        result.put("status", status);
        result.put("errorCode", "");
        result.put("candidates", candidates);
        result.put("modelVersion", "fake");
        result.put("promptVersion", "none");
        result.put("inputTokens", "120");
        result.put("outputTokens", "30");
        result.put("latencyMs", "850");
        return result;
    }

    private Map<String, Object> job() {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", jobId);
    }
}
