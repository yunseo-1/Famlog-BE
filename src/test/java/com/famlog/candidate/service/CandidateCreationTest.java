package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;
import com.famlog.support.PlaceApi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 워커 결과 → 검증·정규화 → 중복 제거 → registration_candidates 저장 (사양서 6.2 ②, 6.3, 6.4, S18).
 * 가짜 워커 결과를 ExtractResultHandler 에 넣는다. 실제 일정·할 일·장소는 만들지 않는다.
 * 6.3 예시는 기준일 2026-11-12(목) 10:00 에 보낸 메시지로 확인한다.
 */
class CandidateCreationTest extends IntegrationTest {

    private static final Instant BASE = OffsetDateTime.parse("2026-11-12T10:00:00+09:00").toInstant();

    @Autowired
    ExtractResultHandler handler;

    @Autowired
    FakeKakaoLocalClient kakao;

    @Autowired
    JsonMapper jsonMapper;

    private Family family;
    private long roomId;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);
        roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
    }

    // ---- 6.3 예시 ----

    @Test
    void 이번_토요일_11시_할머니_댁_가자는_가족_전체_일정이고_오전은_추정이다() throws Exception {
        long aliasId = jdbcTemplate.queryForObject("INSERT INTO place_aliases (group_id, alias, created_by) "
                + "VALUES (?, '할머니댁', ?) RETURNING id", Long.class, family.groupId(), family.mom());
        long m = message(family.mom(), "이번 토요일 11시에 할머니 댁 가자", BASE);
        long job = job(m);

        apply(job, """
                [{"type":"SCHEDULE","fields":{"title":"할머니 댁 방문","dateText":"이번 토요일","startTimeText":"11시",
                  "locationText":"할머니 댁","attendeeTexts":["가자"]},
                  "confidence":0.9,"evidence":[{"messageId":%d,"spans":["이번 토요일 11시","할머니 댁"]}]}]
                """.formatted(m));

        Map<String, Object> row = onlyCandidate();
        JsonNode fields = fields(row);
        assertThat(row).containsEntry("type", "SCHEDULE").containsEntry("status", "PENDING")
                .containsEntry("confidence", 0.9).containsEntry("model_version", "fake")
                .containsEntry("job_id", job);
        assertThat(fields.get("title").asString()).isEqualTo("할머니 댁 방문");
        assertThat(fields.get("date").asString()).isEqualTo("2026-11-14");
        assertThat(fields.get("startTime").asString()).isEqualTo("11:00");
        assertThat(fields.get("allDay").asBoolean()).isFalse();
        assertThat(fields.get("location").get("text").asString()).isEqualTo("할머니 댁");
        assertThat(fields.get("location").get("aliasId").asLong()).isEqualTo(aliasId);
        assertThat(fields.get("attendeeIds").toString()).isEqualTo("[\"ALL\"]");
        assertThat(array(row, "inferred_fields")).containsExactly("startTime.ampm", "attendeeIds");
        assertThat(array(row, "missing_fields")).isEmpty();
        assertThat(((Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-14T11:00:00+09:00").toInstant());
        assertThat(evidence(row)).containsExactly(m + ":[\"이번 토요일 11시\", \"할머니 댁\"]");
        assertThat(candidateCount(job)).isEqualTo(1);
    }

    @Test
    void 아빠가_이번_주에_자동차_점검은_아빠_담당_할_일이고_기한은_일요일_추정이다() throws Exception {
        long m = message(family.mom(), "아빠가 이번 주에 자동차 점검 맡겨야 해", BASE);

        apply(job(m), """
                [{"type":"TODO","fields":{"title":"자동차 점검 맡기기","dueText":"이번 주에","assigneeTexts":["아빠가"]},
                  "confidence":0.85,"evidence":[{"messageId":%d,"spans":["자동차 점검"]}]}]
                """.formatted(m));

        Map<String, Object> row = onlyCandidate();
        JsonNode fields = fields(row);
        assertThat(row).containsEntry("type", "TODO");
        assertThat(fields.get("dueDate").asString()).isEqualTo("2026-11-15");
        assertThat(fields.get("assigneeIds").toString()).isEqualTo("[" + family.dad() + "]");
        assertThat(array(row, "inferred_fields")).containsExactly("dueDate");
    }

    @Test
    void 잠실_칼국수_맛있더라는_장소_후보이고_검색_결과가_하나면_자동으로_고른다() throws Exception {
        String name = "칼국수" + UUID.randomUUID();
        var doc = PlaceApi.doc(name, "FD6", "서울 송파구 잠실동 1", 37.51, 127.08);
        kakao.register("잠실 " + name, true, doc);
        long m = message(family.son(), "잠실에 있는 " + name + " 정말 맛있더라", BASE);

        apply(job(m), """
                [{"type":"PLACE","fields":{"name":"%s","regionHint":"잠실","opinion":"칼국수가 정말 맛있음"},
                  "confidence":0.8,"evidence":[{"messageId":%d,"spans":["%s"]}]}]
                """.formatted(name, m, name));

        Map<String, Object> row = onlyCandidate();
        JsonNode fields = fields(row);
        assertThat(fields.get("kakaoPlaceId").asString()).isEqualTo(doc.id());
        assertThat(fields.get("options")).hasSize(1);
        assertThat(fields.get("options").get(0).get("category").asString()).isEqualTo("RESTAURANT");
        assertThat(fields.get("options").get(0).get("region2").asString()).isEqualTo("송파구");
        assertThat(fields.get("recommendedBy").asLong()).isEqualTo(family.son());
        assertThat(fields.get("opinion").asString()).isEqualTo("칼국수가 정말 맛있음");
        assertThat(row).containsEntry("dedup_key", "PLACE|kakao:" + doc.id());
    }

    @Test
    void 다음_달_15일_할머니_생신은_매년_12월_15일_기념일이다() throws Exception {
        long m = message(family.son(), "다음 달 15일이 할머니 생신이야", BASE);

        apply(job(m), """
                [{"type":"ANNIVERSARY","fields":{"title":"할머니 생신","dateText":"다음 달 15일","personText":"할머니"},
                  "confidence":0.9,"evidence":[{"messageId":%d,"spans":["다음 달 15일"]}]}]
                """.formatted(m));

        Map<String, Object> row = onlyCandidate();
        JsonNode fields = fields(row);
        assertThat(fields.get("month").asInt()).isEqualTo(12);
        assertThat(fields.get("day").asInt()).isEqualTo(15);
        assertThat(fields.get("personText").asString()).isEqualTo("할머니");
        assertThat(row).containsEntry("dedup_key", "ANNIVERSARY|할머니생신|12-15");
    }

    @Test
    void 다음_주_토요일_6시_할머니댁_저녁은_11월_21일_오후_6시다() throws Exception {
        long m = message(family.dad(), "다음 주 토요일 6시에 할머니댁 저녁", BASE);

        apply(job(m), """
                [{"type":"SCHEDULE","fields":{"title":"할머니댁 저녁","dateText":"다음 주 토요일","startTimeText":"6시",
                  "locationText":"할머니댁"},"confidence":0.9,"evidence":[{"messageId":%d,"spans":["다음 주 토요일 6시"]}]}]
                """.formatted(m));

        JsonNode fields = fields(onlyCandidate());
        assertThat(fields.get("date").asString()).isEqualTo("2026-11-21");
        assertThat(fields.get("startTime").asString()).isEqualTo("18:00");
        assertThat(fields.get("location").get("aliasId").isNull()).isTrue();
    }

    // ---- 신뢰도·6.4·검증 ----

    @Test
    void 신뢰도_0_5_미만은_버리고_0_5_이상은_저장한다() throws Exception {
        long m = message(family.mom(), "내일 장보기", Instant.now());

        apply(job(m), "[" + todo("장보기", "내일", m, 0.4) + "," + todo("세탁소", "내일", m, 0.6) + "]");

        Map<String, Object> row = onlyCandidate();
        assertThat(fields(row).get("title").asString()).isEqualTo("세탁소");
        assertThat(row).containsEntry("confidence", 0.6);
    }

    @Test
    void 지난_날짜의_일정과_지난_기한의_할_일은_만들지_않는다() throws Exception {
        long m = message(family.mom(), "어제 할머니 댁 갔었지", BASE);

        long job = job(m);
        apply(job, """
                [{"type":"SCHEDULE","fields":{"title":"할머니 댁 방문","dateText":"어제"},
                  "confidence":0.9,"evidence":[{"messageId":%d}]},
                 %s]
                """.formatted(m, todo("숙제", "지난주까지", m, 0.9)));

        assertThat(candidates()).isEmpty();
        assertThat(candidateCount(job)).isZero();
    }

    @Test
    void 날짜를_해석하지_못한_일정은_날짜를_채워야_하는_후보로_만든다() throws Exception {
        long m = message(family.mom(), "언제 한번 영화 보자", BASE);

        apply(job(m), """
                [{"type":"SCHEDULE","fields":{"title":"영화 보기","dateText":"언제 한번"},
                  "confidence":0.6,"evidence":[{"messageId":%d}]}]
                """.formatted(m));

        Map<String, Object> row = onlyCandidate();
        assertThat(array(row, "missing_fields")).containsExactly("date");
        assertThat(fields(row).get("date").isNull()).isTrue();
        assertThat(fields(row).get("allDay").asBoolean()).isTrue();
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        assertThat(expiresAt).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    void 근거가_분석_구간_밖이거나_지운_메시지면_그_후보만_버린다() throws Exception {
        long before = message(family.mom(), "내일 병원", Instant.now());
        long inRange = message(family.mom(), "내일 세탁소", Instant.now());
        long deleted = message(family.mom(), "내일 은행", Instant.now());
        jdbcTemplate.update("UPDATE messages SET deleted_at = now(), body = NULL WHERE id = ?", deleted);
        long job = job(seqOf(before), seqOf(deleted)); // (before, deleted] = inRange, deleted

        apply(job, "[" + todo("병원", "내일", before, 0.9) + "," + todo("세탁소", "내일", inRange, 0.9) + ","
                + todo("은행", "내일", deleted, 0.9) + "]");

        assertThat(candidates()).extracting(row -> fields(row).get("title").asString()).containsExactly("세탁소");
    }

    @Test
    void 다른_그룹의_메시지를_근거로_대면_버린다() throws Exception {
        Family other = Family.create(mockMvc, this::createUser);
        long otherRoom = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                other.groupId());
        long foreign = jdbcTemplate.queryForObject("""
                INSERT INTO messages (room_id, seq, sender_id, type, body)
                VALUES (?, (SELECT last_seq + 100 FROM chat_rooms WHERE id = ?), ?, 'TEXT', '내일 병원') RETURNING id
                """, Long.class, otherRoom, otherRoom, other.mom());
        long mine = message(family.mom(), "내일 세탁소", Instant.now());

        apply(job(mine), "[" + todo("병원", "내일", foreign, 0.9) + "]");

        assertThat(candidates()).isEmpty();
    }

    @Test
    void 결과가_왔을_때_그룹_AI가_꺼져_있으면_저장하지_않는다() throws Exception {
        long m = message(family.mom(), "내일 세탁소", Instant.now());
        long job = job(m);
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), false);

        apply(job, "[" + todo("세탁소", "내일", m, 0.9) + "]");

        assertThat(candidates()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_jobs WHERE id = ?", String.class, job))
                .isEqualTo("DONE");
    }

    @Test
    void 형식이_잘못된_후보가_있어도_나머지는_저장한다() throws Exception {
        long m = message(family.mom(), "내일 세탁소", Instant.now());

        apply(job(m), "[{\"type\":\"MEETING\"}," + todo("세탁소", "내일", m, 0.9) + "]");

        assertThat(candidates()).hasSize(1);
    }

    // ---- 중복 ----

    @Test
    void 같은_내용을_다시_말하면_근거만_추가한다() throws Exception {
        long first = message(family.mom(), "내일 세탁소 가야 해", Instant.now());
        apply(job(first), "[" + todo("세탁소 가기", "내일", first, 0.9) + "]");
        long second = message(family.dad(), "내일 세탁소 들러야 해", Instant.now());

        long job = job(second);
        apply(job, "[" + todo("세탁소가기!", "내일", second, 0.8) + "]");

        Map<String, Object> row = onlyCandidate();
        assertThat(evidence(row)).hasSize(2);
        assertThat(candidateCount(job)).isZero();
    }

    @Test
    void 한_결과_안에_같은_후보가_두_번_있으면_하나만_만든다() throws Exception {
        long m = message(family.mom(), "내일 세탁소, 내일 세탁소!", Instant.now());

        apply(job(m), "[" + todo("세탁소", "내일", m, 0.9) + "," + todo("세탁소", "내일", m, 0.9) + "]");

        assertThat(candidates()).hasSize(1);
    }

    @Test
    void 무시한_후보와_같으면_만들지_않는다() throws Exception {
        long first = message(family.mom(), "내일 세탁소", Instant.now());
        apply(job(first), "[" + todo("세탁소", "내일", first, 0.9) + "]");
        jdbcTemplate.update("UPDATE registration_candidates SET status = 'DISMISSED' WHERE group_id = ?",
                family.groupId());
        long again = message(family.mom(), "내일 세탁소 꼭", Instant.now());

        apply(job(again), "[" + todo("세탁소", "내일", again, 0.9) + "]");

        assertThat(candidates()).singleElement().extracting(row -> row.get("status")).isEqualTo("DISMISSED");
    }

    @Test
    void 만료된_후보와_같으면_새로_만든다() throws Exception {
        long first = message(family.mom(), "내일 세탁소", Instant.now());
        apply(job(first), "[" + todo("세탁소", "내일", first, 0.9) + "]");
        jdbcTemplate.update("UPDATE registration_candidates SET expires_at = now() - interval '1 minute' "
                + "WHERE group_id = ?", family.groupId());
        long again = message(family.mom(), "내일 세탁소 꼭", Instant.now());

        apply(job(again), "[" + todo("세탁소", "내일", again, 0.9) + "]");

        assertThat(candidates()).hasSize(2);
    }

    // ---- 구성원 ----

    @Test
    void 같은_역할이_여러_명이면_고를_후보로_두고_나는_보낸_사람이다() throws Exception {
        long secondSon = createUser();
        GroupApi.addMember(mockMvc, family.groupId(), family.mom(), secondSon);
        jdbcTemplate.update("UPDATE group_members SET family_role = 'SON' WHERE group_id = ? AND user_id = ?",
                family.groupId(), secondSon);
        long m = message(family.dad(), "내일 나랑 아들이 분리수거", Instant.now());

        apply(job(m), """
                [{"type":"TODO","fields":{"title":"분리수거","dueText":"내일","assigneeTexts":["나","아들","삼촌"]},
                  "confidence":0.9,"evidence":[{"messageId":%d}]}]
                """.formatted(m));

        JsonNode fields = fields(onlyCandidate());
        assertThat(fields.get("assigneeIds").toString()).isEqualTo("[" + family.dad() + "]");
        assertThat(fields.get("assigneeChoices").toString())
                .isEqualTo("[[" + family.son() + "," + secondSon + "]]");
        assertThat(fields.get("unmatchedAssignees").toString()).isEqualTo("[\"삼촌\"]");
    }

    // ---- 장소 검색 ----

    @Test
    void 검색_결과가_여럿이면_상위_3개만_두고_고르지_않는다() throws Exception {
        String name = "카페" + UUID.randomUUID();
        kakao.register(name, false, PlaceApi.doc(name + "1", "CE7", "서울 중구 1", 37.5, 127.0),
                PlaceApi.doc(name + "2", "CE7", "서울 중구 2", 37.5, 127.0),
                PlaceApi.doc(name + "3", "CE7", "서울 중구 3", 37.5, 127.0),
                PlaceApi.doc(name + "4", "CE7", "서울 중구 4", 37.5, 127.0));
        long m = message(family.son(), name + " 가볼 만해", BASE);

        apply(job(m), place(name, m));

        Map<String, Object> row = onlyCandidate();
        assertThat(fields(row).get("options")).hasSize(3);
        assertThat(fields(row).get("kakaoPlaceId").isNull()).isTrue();
        assertThat(row.get("dedup_key")).isEqualTo("PLACE|name:" + DedupKeys.normalize(name));
    }

    @Test
    void 검색_결과가_없거나_카카오가_실패해도_이름으로_저장한다() throws Exception {
        String missing = "없는집" + UUID.randomUUID();
        String failing = "장애집" + UUID.randomUUID();
        kakao.fail(failing);
        long m = message(family.son(), missing + " " + failing + " 맛있더라", BASE);

        apply(job(m), "[" + place(missing, m).replaceAll("^\\[|]$", "") + ","
                + place(failing, m).replaceAll("^\\[|]$", "") + "]");

        assertThat(candidates()).hasSize(2).allSatisfy(row -> {
            assertThat(fields(row).get("options")).isEmpty();
            assertThat(fields(row).get("kakaoPlaceId").isNull()).isTrue();
        });
    }

    // ---- 도움 함수 ----

    private static String todo(String title, String dueText, long messageId, double confidence) {
        return """
                {"type":"TODO","fields":{"title":"%s","dueText":"%s"},"confidence":%s,
                 "evidence":[{"messageId":%d}]}""".formatted(title, dueText, confidence, messageId);
    }

    private static String place(String name, long messageId) {
        return """
                [{"type":"PLACE","fields":{"name":"%s"},"confidence":0.8,"evidence":[{"messageId":%d}]}]"""
                .formatted(name, messageId);
    }

    private long message(long senderId, String body, Instant sentAt) {
        long seq = jdbcTemplate.queryForObject("SELECT last_seq FROM chat_rooms WHERE id = ?", Long.class, roomId) + 1;
        jdbcTemplate.update("UPDATE chat_rooms SET last_seq = ? WHERE id = ?", seq, roomId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO messages (room_id, seq, sender_id, type, body, created_at)
                VALUES (?, ?, ?, 'TEXT', ?, ?) RETURNING id
                """, Long.class, roomId, seq, senderId, body, Timestamp.from(sentAt));
    }

    private long seqOf(long messageId) {
        return jdbcTemplate.queryForObject("SELECT seq FROM messages WHERE id = ?", Long.class, messageId);
    }

    /** 이 메시지 하나가 분석 구간인 요청 */
    private long job(long messageId) {
        long s = seqOf(messageId);
        return job(s - 1, s);
    }

    private long job(long fromSeq, long toSeq) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs)
                VALUES (?, ?, ?, ?, 'RULE', 1, '{}') RETURNING id
                """, Long.class, family.groupId(), roomId, fromSeq, toSeq);
    }

    private void apply(long jobId, String candidates) {
        Map<String, String> result = new HashMap<>();
        result.put("jobId", Long.toString(jobId));
        result.put("status", "DONE");
        result.put("errorCode", "");
        result.put("candidates", candidates);
        result.put("modelVersion", "fake");
        result.put("promptVersion", "none");
        handler.apply(result);
    }

    private List<Map<String, Object>> candidates() {
        return jdbcTemplate.queryForList("SELECT * FROM registration_candidates WHERE group_id = ? ORDER BY id",
                family.groupId());
    }

    private Map<String, Object> onlyCandidate() {
        List<Map<String, Object>> rows = candidates();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private JsonNode fields(Map<String, Object> row) {
        return jsonMapper.readTree(row.get("fields").toString());
    }

    private static List<String> array(Map<String, Object> row, String column) {
        try {
            return List.of((String[]) ((java.sql.Array) row.get(column)).getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "messageId:spans" 목록 */
    private List<String> evidence(Map<String, Object> row) {
        return jdbcTemplate.queryForList("""
                SELECT message_id || ':' || spans::text FROM candidate_evidence WHERE candidate_id = ?
                ORDER BY message_id
                """, String.class, row.get("id"));
    }

    private int candidateCount(long jobId) {
        return jdbcTemplate.queryForObject("SELECT candidate_count FROM ai_jobs WHERE id = ?", Integer.class, jobId);
    }
}
