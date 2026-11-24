package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화 분석 트리거 (사양서 4.5 ARC-01, 6.2 ①, 8.7): 규칙 필터 + 30초 멈춤, 보조 분석(30개·10분),
 * 마스킹·가명 처리, 분석 제외(AR-10), 그룹 AI 꺼짐(SET-01). LLM 은 부르지 않는다 (요청이 스트림에 들어가는 데까지).
 * 스케줄러는 꺼져 있고 runDue 를 시각을 정해 직접 부른다.
 */
class AiTriggerTest extends ChatIntegrationTest {

    private static final String JOBS = "famlog:ai.extract";

    @Autowired
    AiTriggerService triggerService;

    @Autowired
    JsonMapper jsonMapper;

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        GroupApi.setFamilyTitle(mockMvc, family.groupId(), family.son(), "막내");
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);
    }

    @Test
    void 규칙에_걸린_메시지는_대화가_5초_멈춘_뒤_분석_요청이_된다() {
        long enabledSeq = roomLastSeq(family.groupId());
        long id = send(family.groupId(), family.mom(), "이번 토요일 11시에 할머니 댁 가자").id();

        triggerService.runDue(Instant.now().plusSeconds(2));
        assertThat(jobs()).isEmpty();

        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(6)));
        Map<String, Object> job = onlyJob();
        assertThat(job).containsEntry("trigger", "RULE").containsEntry("message_count", 1)
                .containsEntry("from_seq", enabledSeq).containsEntry("to_seq", roomLastSeq(family.groupId()))
                .containsEntry("status", "QUEUED");

        JsonNode payload = payloadOf(job);
        assertThat(payload.get("timezone").asString()).isEqualTo("Asia/Seoul");
        JsonNode message = payload.get("messages").get(0);
        assertThat(message.get("id").asLong()).isEqualTo(id);
        assertThat(message.get("sender").asString()).isEqualTo("M1");
        assertThat(message.get("text").asString()).isEqualTo("이번 토요일 11시에 할머니 댁 가자");
        assertThat(message.get("sentAt").asString()).endsWith("+09:00");
    }

    @Test
    void 구성원은_임시_ID와_역할_호칭만_보내고_대응은_요청_기록에_남긴다() throws Exception {
        send(family.groupId(), family.son(), "내일 7시 저녁 먹자");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        Map<String, Object> job = onlyJob();
        JsonNode payload = payloadOf(job);
        assertThat(payload.get("members").toString())
                .isEqualTo("[{\"ref\":\"M1\",\"familyRole\":\"MOTHER\",\"familyTitle\":null},"
                        + "{\"ref\":\"M2\",\"familyRole\":\"FATHER\",\"familyTitle\":null},"
                        + "{\"ref\":\"M3\",\"familyRole\":\"SON\",\"familyTitle\":\"막내\"}]");
        assertThat(payload.get("messages").get(0).get("sender").asString()).isEqualTo("M3");
        // 닉네임·사용자 ID는 보내지 않는다
        String raw = rawPayload(job);
        assertThat(raw).doesNotContain("테스트").doesNotContain("\"" + family.son() + "\"")
                .doesNotContain("userId").doesNotContain("nickname");
        assertThat(jsonMapper.readTree(job.get("member_refs").toString()).toString()).isEqualTo(
                "{\"M1\":%d,\"M2\":%d,\"M3\":%d}".formatted(family.mom(), family.dad(), family.son()));
    }

    @Test
    void 전화번호_계좌번호_이메일은_가려서_보낸다() {
        send(family.groupId(), family.dad(), "내일까지 110-123-456789 로 보내고 010-1234-5678 로 연락해, a@b.com");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        String text = payloadOf(onlyJob()).get("messages").get(0).get("text").asString();
        assertThat(text).isEqualTo("내일까지 [계좌번호] 로 보내고 [전화번호] 로 연락해, [이메일]");
    }

    @Test
    void 규칙에_안_걸린_메시지는_10분_멈춘_뒤_보조_분석된다() {
        send(family.groupId(), family.mom(), "ㅋㅋㅋ 귀엽다");

        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));
        assertThat(jobs()).isEmpty();

        triggerService.runDue(Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThat(onlyJob()).containsEntry("trigger", "BATCH");
    }

    @Test
    void 분석_안_된_메시지가_30개_쌓이면_바로_보조_분석하고_다음_요청은_앞_맥락_20개를_싣는다() {
        for (int i = 1; i <= 30; i++) {
            send(family.groupId(), i % 2 == 0 ? family.mom() : family.son(), "응 " + i);
        }
        triggerService.runDue(Instant.now());
        Map<String, Object> first = onlyJob();
        assertThat(first).containsEntry("trigger", "BATCH").containsEntry("message_count", 30);
        assertThat(payloadOf(first).get("context")).isEmpty();

        send(family.groupId(), family.dad(), "토요일에 다 같이 가자");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        List<Map<String, Object>> jobs = jobs();
        assertThat(jobs).hasSize(2);
        JsonNode second = payloadOf(jobs.get(1));
        assertThat(second.get("messages")).hasSize(1);
        assertThat(second.get("context")).hasSize(20);
        assertThat(second.get("context").get(0).get("text").asString()).isEqualTo("응 11");
        assertThat(second.get("context").get(19).get("text").asString()).isEqualTo("응 30");
        assertThat(jobs.get(1).get("from_seq")).isEqualTo(first.get("to_seq"));
    }

    @Test
    void 같은_구간은_두_번_보내지_않는다() {
        send(family.groupId(), family.mom(), "내일 장 보자");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));
        triggerService.runDue(Instant.now().plus(Duration.ofMinutes(20)));

        assertThat(jobs()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT ai_analyzed_seq FROM chat_rooms WHERE group_id = ?",
                Long.class, family.groupId())).isEqualTo(roomLastSeq(family.groupId()));
    }

    @Test
    void 새_메시지가_오면_분석_시각이_미뤄진다() {
        send(family.groupId(), family.mom(), "내일 11시 어때");
        double first = dueScore();

        send(family.groupId(), family.dad(), "좋아");
        double second = dueScore();

        assertThat(second).isGreaterThanOrEqualTo(first);
        assertThat(second).isLessThanOrEqualTo(Instant.now().plus(Duration.ofSeconds(30)).toEpochMilli());
    }

    @Test
    void 분석_제외한_사람의_메시지는_큐에_넣지_않는다_AR10() throws Exception {
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.dad(), true);

        send(family.groupId(), family.dad(), "토요일 11시에 아빠 차 맡겨야 해");
        assertThat(redis.hasKey(AiTriggerService.roomKey(roomId()))).isFalse();
        triggerService.runDue(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(jobs()).isEmpty();

        send(family.groupId(), family.mom(), "그럼 일요일에 가자");
        send(family.groupId(), family.dad(), "일요일 2시 좋아");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        Map<String, Object> job = onlyJob();
        assertThat(job).containsEntry("message_count", 1);
        String raw = rawPayload(job);
        assertThat(raw).contains("그럼 일요일에 가자").doesNotContain("아빠 차").doesNotContain("일요일 2시");
        // 분석 제외한 사람도 담당자·참석자가 될 수 있어 구성원 목록에는 역할만 남는다
        assertThat(payloadOf(job).get("members")).hasSize(3);
    }

    @Test
    void 분석_제외했던_동안의_메시지는_다시_켜도_보내지_않는다() throws Exception {
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.dad(), true);
        send(family.groupId(), family.dad(), "비밀번호는 내 생일이야");
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.dad(), false);

        send(family.groupId(), family.mom(), "내일 생일 케이크 사 와");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));
        send(family.groupId(), family.dad(), "알겠어 내일 살게");
        triggerService.runDue(Instant.now().plus(Duration.ofMinutes(1)));

        List<Map<String, Object>> jobs = jobs();
        assertThat(jobs).hasSize(2);
        assertThat(jobs).allSatisfy(job -> assertThat(rawPayload(job)).doesNotContain("비밀번호"));
        assertThat(rawPayload(jobs.get(1))).contains("알겠어 내일 살게");
    }

    @Test
    void 그룹_AI_분석이_꺼져_있으면_요청을_만들지_않는다_SET01() throws Exception {
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), false);

        send(family.groupId(), family.mom(), "이번 토요일 11시에 할머니 댁 가자");
        triggerService.runDue(Instant.now().plus(Duration.ofMinutes(11)));

        assertThat(redis.hasKey(AiTriggerService.roomKey(roomId()))).isFalse();
        assertThat(jobs()).isEmpty();
    }

    @Test
    void 분석을_기다리는_중에_그룹_AI를_끄면_보내지_않는다() throws Exception {
        send(family.groupId(), family.mom(), "이번 토요일 11시에 할머니 댁 가자");
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), false);

        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        assertThat(jobs()).isEmpty();
    }

    @Test
    void 켜기_전_대화는_분석에도_앞_맥락에도_보내지_않는다() throws Exception {
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), false);
        send(family.groupId(), family.mom(), "켜기 전 이야기 내일 하자");
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);

        send(family.groupId(), family.son(), "토요일 6시 저녁");
        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        String raw = rawPayload(onlyJob());
        assertThat(raw).contains("토요일 6시 저녁").doesNotContain("켜기 전");
    }

    @Test
    void 지운_메시지는_보내지_않는다() {
        long id = send(family.groupId(), family.mom(), "내일 10시 병원").id();
        chatService.delete(id, family.mom());

        triggerService.runDue(Instant.now().plus(Duration.ofSeconds(31)));

        assertThat(jobs()).isEmpty();
    }

    private long roomId() {
        return jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
    }

    private double dueScore() {
        Double score = redis.opsForZSet().score(AiTriggerService.DUE_KEY, Long.toString(roomId()));
        assertThat(score).isNotNull();
        return score;
    }

    private List<Map<String, Object>> jobs() {
        return jdbcTemplate.queryForList("SELECT * FROM ai_jobs WHERE group_id = ? ORDER BY id", family.groupId());
    }

    private Map<String, Object> onlyJob() {
        List<Map<String, Object>> jobs = jobs();
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    private String rawPayload(Map<String, Object> job) {
        String jobId = job.get("id").toString();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(JOBS, Range.unbounded());
        return records.stream().map(MapRecord::getValue).filter(value -> jobId.equals(value.get("jobId")))
                .map(value -> value.get("payload").toString()).findFirst().orElseThrow();
    }

    private JsonNode payloadOf(Map<String, Object> job) {
        return jsonMapper.readTree(rawPayload(job));
    }
}
