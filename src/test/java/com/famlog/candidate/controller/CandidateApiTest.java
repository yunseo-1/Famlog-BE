package com.famlog.candidate.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.candidate.service.ExtractResultHandler;
import com.famlog.common.time.FamlogTime;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.PlaceApi;
import com.famlog.support.ScheduleApi;
import com.famlog.support.StompTestClient;
import com.jayway.jsonpath.JsonPath;

/**
 * 후보 API (사양서 6.2 ③~⑤, 6.5 AR-2~7, 11.3 ARC-05, ARC-06). 후보는 S18 경로(가짜 워커 결과 → ExtractResultHandler)로 만든다.
 * 메시지는 지금 보내고 "내일" 일정으로 만들어 만료되지 않게 한다.
 */
class CandidateApiTest extends ChatIntegrationTest {

    @Autowired
    ExtractResultHandler handler;

    @Autowired
    FakeKakaoLocalClient kakao;

    private Family family;
    private long roomId;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);
        roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        tomorrow = ZonedDateTime.now(FamlogTime.DISPLAY_ZONE).toLocalDate().plusDays(1);
    }

    // ---- AR-2·목록·상세 ----

    @Test
    void 대기_중인_후보는_캘린더와_할_일_목록에_나오지_않는다() throws Exception {
        schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        todo(family.mom(), "아빠 내일까지 택배 반품 보내줘", "택배 반품", "내일까지", "아빠", 0.9);

        mockMvc.perform(get("/api/v1/groups/{g}/schedules", family.groupId()).with(asUser(family.mom()))
                        .param("from", tomorrow.toString()).param("to", tomorrow.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/groups/{g}/todos", family.groupId()).with(asUser(family.mom())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(count("registration_candidates WHERE group_id = " + family.groupId())).isEqualTo(2);
    }

    @Test
    void 목록은_카드와_제안함을_나누고_카드를_붙일_메시지를_알려준다() throws Exception {
        long card = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        long messageId = lastMessageId();
        long seq = lastSeq();
        long inbox = todo(family.dad(), "내일 세차할까", "세차", "내일", null, 0.6);

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(inbox))
                .andExpect(jsonPath("$.items[0].surface").value("INBOX"))
                .andExpect(jsonPath("$.items[1].id").value(card))
                .andExpect(jsonPath("$.items[1].surface").value("CARD"))
                .andExpect(jsonPath("$.items[1].anchorMessageId").value(messageId))
                .andExpect(jsonPath("$.items[1].anchorSeq").value(seq))
                .andExpect(jsonPath("$.items[1].fields.date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.items[1].inferred[0]").value("startTime.ampm"))
                .andExpect(jsonPath("$.items[1].status").value("PENDING"));
        list("&toSeq=" + seq).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(card));
        list("&size=1").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(Long.toString(inbox)));
        list("&size=1&cursor=" + inbox).andExpect(jsonPath("$.items[0].id").value(card))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 만료된_후보는_목록에_없고_승인하면_409() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        jdbcTemplate.update("UPDATE registration_candidates SET expires_at = now() - interval '1 minute' WHERE id = ?",
                id);

        list("").andExpect(jsonPath("$.items.length()").value(0));
        approve(family.mom(), id, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANDIDATE_EXPIRED"));
    }

    @Test
    void 상세에는_근거_메시지_본문_보낸_사람_시각_추출_부분이_있다() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);

        mockMvc.perform(get("/api/v1/candidates/{id}", id).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.id").value(id))
                .andExpect(jsonPath("$.evidence[0].text").value("내일 11시에 할머니 댁 가자"))
                .andExpect(jsonPath("$.evidence[0].senderId").value(family.mom()))
                .andExpect(jsonPath("$.evidence[0].sentAt").exists())
                .andExpect(jsonPath("$.evidence[0].spans[0]").value("내일 11시"));
    }

    // ---- 승인 ----

    @Test
    void 일정을_그대로_승인하면_가족_전체_일정과_원문_연결_시스템_메시지가_생긴다() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        long evidence = lastMessageId();

        String body = approve(family.dad(), id, "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("APPROVED"))
                .andExpect(jsonPath("$.candidate.entityType").value("SCHEDULE"))
                .andExpect(jsonPath("$.candidate.decidedBy").value(family.dad()))
                .andReturn().getResponse().getContentAsString();
        long scheduleId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();

        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("할머니 댁 방문"))
                .andExpect(jsonPath("$.allDay").value(false))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T11:00:00+09:00"))
                .andExpect(jsonPath("$.participantIds.length()").value(3))
                .andExpect(jsonPath("$.memo").value("장소: 할머니 댁"))
                .andExpect(jsonPath("$.createdBy").value(family.dad()))
                .andExpect(jsonPath("$.source.messageId").value(evidence))
                .andExpect(jsonPath("$.source.candidateId").value(id));
        mockMvc.perform(get("/api/v1/groups/{g}/schedules", family.groupId()).with(asUser(family.son()))
                        .param("from", tomorrow.toString()).param("to", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.scheduleId == %d)].source.candidateId".formatted(scheduleId))
                        .value(org.hamcrest.Matchers.contains((int) id)));
        assertThat(feedback(id)).containsExactly("APPROVED:{}");
        Map<String, Object> system = jdbcTemplate.queryForMap("SELECT body, payload::text AS payload FROM messages "
                + "WHERE room_id = ? AND type = 'SYSTEM' ORDER BY seq DESC LIMIT 1", roomId);
        assertThat((String) system.get("body")).endsWith("님이 '할머니 댁 방문' 일정을 캘린더에 등록했어요.");
        assertThat((String) system.get("payload")).contains("\"kind\": \"CANDIDATE_APPROVED\"",
                "\"candidateId\": " + id);
    }

    @Test
    void 시각을_고쳐_승인하면_고친_값으로_저장하고_EDITED를_남긴다() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);

        String body = approve(family.mom(), id, """
                {"version":0,"schedule":{"title":"할머니 댁 방문","allDay":false,
                 "startsAt":"%sT12:00:00+09:00","endsAt":"%sT12:00:00+09:00","participantIds":[%d,%d,%d],
                 "memo":"장소: 할머니 댁"}}""".formatted(tomorrow, tomorrow, family.mom(), family.dad(), family.son()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long scheduleId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();

        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T12:00:00+09:00"));
        assertThat(feedback(id)).containsExactly("EDITED:{startsAt,endsAt}");
    }

    @Test
    void 날짜가_빈_후보는_그대로_등록할_수_없고_채우면_등록된다() throws Exception {
        long id = candidate(family.mom(), "언제 한번 영화 보자", """
                {"type":"SCHEDULE","fields":{"title":"영화 보기","dateText":"언제 한번"},"confidence":0.8,
                 "evidence":[{"messageId":%d}]}""");

        approve(family.mom(), id, "{\"version\":0}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CANDIDATE_MISSING_FIELDS"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"));
        approve(family.mom(), id, """
                {"version":0,"schedule":{"title":"영화 보기","allDay":true,"startDate":"%s","endDate":"%s",
                 "participantIds":[%d]}}""".formatted(tomorrow, tomorrow, family.mom()))
                .andExpect(status().isOk());

        assertThat(feedback(id)).containsExactly("EDITED:{date}");
    }

    @Test
    void 두_사람이_동시에_승인해도_일정은_하나이고_늦은_쪽은_409() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (long user : List.of(family.mom(), family.dad())) {
            results.add(pool.submit(() -> {
                start.await();
                return approve(user, id, "{\"version\":0}").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("schedules WHERE group_id = " + family.groupId())).isEqualTo(1);
        long winner = jdbcTemplate.queryForObject("SELECT decided_by FROM registration_candidates WHERE id = ?",
                Long.class, id);
        long loser = winner == family.mom() ? family.dad() : family.mom();
        approve(loser, id, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANDIDATE_ALREADY_DECIDED"))
                .andExpect(jsonPath("$.message").value("테스트님이 먼저 등록했어요."));
        approve(winner, id, "{\"version\":0}").andExpect(status().isOk());
        assertThat(count("schedules WHERE group_id = " + family.groupId())).isEqualTo(1);
    }

    @Test
    void 조회한_뒤_바뀐_후보는_409_VERSION_CONFLICT() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);

        approve(family.mom(), id, "{\"version\":3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void 기념일은_매년_반복하는_가족_전체_종일_일정이_된다() throws Exception {
        long id = candidate(family.son(), "다음 달 15일이 할머니 생신이야", """
                {"type":"ANNIVERSARY","fields":{"title":"할머니 생신","dateText":"다음 달 15일","personText":"할머니"},
                 "confidence":0.9,"evidence":[{"messageId":%d}]}""");

        String body = approve(family.mom(), id, "{\"version\":0}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long scheduleId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.rrule").value("FREQ=YEARLY"))
                .andExpect(jsonPath("$.startDate").value(org.hamcrest.Matchers.endsWith("-15")))
                .andExpect(jsonPath("$.participantIds.length()").value(3))
                .andExpect(jsonPath("$.memo").value("대상: 할머니"));
    }

    @Test
    void 할_일은_담당자_한_명이면_그대로_두_명이면_골라야_한다() throws Exception {
        long one = todo(family.mom(), "아빠 내일까지 택배 반품 보내줘", "택배 반품", "내일까지", "아빠", 0.9);
        long two = todo(family.mom(), "아빠랑 아들 내일 분리수거", "분리수거", "내일", "아빠랑 아들", 0.9);

        String body = approve(family.son(), one, "{\"version\":0}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long todoId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();
        mockMvc.perform(get("/api/v1/todos/{id}", todoId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.assigneeId").value(family.dad()))
                .andExpect(jsonPath("$.dueDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.source.seq").exists())
                .andExpect(jsonPath("$.source.candidateId").value(one));

        approve(family.son(), two, "{\"version\":0}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assigneeIds"));
        approve(family.son(), two, """
                {"version":0,"todo":{"title":"분리수거","assigneeId":%d,"dueDate":"%s"}}"""
                .formatted(family.son(), tomorrow)).andExpect(status().isOk());
        assertThat(feedback(two)).containsExactly("EDITED:{assigneeIds}");
    }

    @Test
    void 장소는_검색_결과가_하나면_바로_여럿이면_골라서_등록한다() throws Exception {
        String name = "칼국수" + UUID.randomUUID();
        var only = PlaceApi.doc(name, "FD6", "서울 송파구 잠실동 1", 37.51, 127.08);
        kakao.register(name, true, only);
        long single = place(family.son(), name, "칼국수가 정말 맛있음");

        String body = approve(family.mom(), single, "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.linkedExisting").value(false))
                .andReturn().getResponse().getContentAsString();
        long placeId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();
        mockMvc.perform(get("/api/v1/places/{id}", placeId).with(asUser(family.son())))
                .andExpect(jsonPath("$.kakaoPlaceId").value(only.id()))
                .andExpect(jsonPath("$.category").value("RESTAURANT"))
                .andExpect(jsonPath("$.oneLineReview").value("칼국수가 정말 맛있음"))
                .andExpect(jsonPath("$.sharedBy").value(family.mom()))
                .andExpect(jsonPath("$.source.messageId").exists())
                .andExpect(jsonPath("$.source.candidateId").value(single));

        String cafe = "카페" + UUID.randomUUID();
        var first = PlaceApi.doc(cafe + "1", "CE7", "서울 중구 1", 37.5, 127.0);
        var second = PlaceApi.doc(cafe + "2", "CE7", "서울 중구 2", 37.5, 127.0);
        kakao.register(cafe, false, first, second);
        long many = place(family.son(), cafe, null);
        approve(family.mom(), many, "{\"version\":0}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("kakaoPlaceId"));
        approve(family.mom(), many, """
                {"version":0,"place":{"kakaoPlaceId":"%s","category":"CAFE"}}""".formatted(second.id()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM places WHERE group_id = ? AND kakao_place_id = ?",
                Integer.class, family.groupId(), second.id())).isEqualTo(1);
    }

    @Test
    void 이미_공유한_장소면_새로_만들지_않고_연결하고_되돌려도_장소는_남는다() throws Exception {
        String name = "냉면" + UUID.randomUUID();
        var doc = PlaceApi.doc(name, "FD6", "서울 중구 1", 37.5, 127.0);
        long shared = PlaceApi.shareNew(mockMvc, kakao, family.groupId(), family.dad(), doc, "RESTAURANT");
        kakao.register(name, true, doc);
        long id = place(family.son(), name, "맛있음");

        approve(family.mom(), id, "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.entityId").value(shared))
                .andExpect(jsonPath("$.candidate.entityType").value("PLACE"))
                .andExpect(jsonPath("$.candidate.linkedExisting").value(true));
        mockMvc.perform(get("/api/v1/places/{id}", shared).with(asUser(family.mom())))
                .andExpect(jsonPath("$.source.messageId").exists())
                .andExpect(jsonPath("$.source.candidateId").value(id));

        undo(family.mom(), id).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/places/{id}", shared).with(asUser(family.mom())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void 한_메시지에서_나온_후보가_여럿이면_항목마다_자기를_만든_후보를_원문으로_가리킨다() throws Exception {
        long scheduleCandidate = candidate(family.mom(), "내일 11시에 할머니 댁 가자 아빠는 선물 사 와", """
                {"type":"SCHEDULE","fields":{"title":"할머니 댁 방문","dateText":"내일","startTimeText":"11시",
                 "attendeeTexts":["가자"]},"confidence":0.9,"evidence":[{"messageId":%1$d,"spans":["내일 11시"]}]},
                {"type":"TODO","fields":{"title":"선물 사기","assigneeTexts":["아빠"]},"confidence":0.9,
                 "evidence":[{"messageId":%1$d,"spans":["선물 사 와"]}]}""");
        long todoCandidate = jdbcTemplate.queryForObject("""
                SELECT id FROM registration_candidates WHERE job_id = (SELECT job_id FROM registration_candidates
                WHERE id = ?) AND type = 'TODO'""", Long.class, scheduleCandidate);

        long scheduleId = ((Number) JsonPath.read(approve(family.dad(), scheduleCandidate, "{\"version\":0}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.candidate.entityId"))
                .longValue();
        long todoId = ((Number) JsonPath.read(approve(family.dad(), todoCandidate, "{\"version\":0}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.candidate.entityId"))
                .longValue();

        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.son())))
                .andExpect(jsonPath("$.source.candidateId").value(scheduleCandidate));
        mockMvc.perform(get("/api/v1/todos/{id}", todoId).with(asUser(family.son())))
                .andExpect(jsonPath("$.source.candidateId").value(todoCandidate));
        mockMvc.perform(get("/api/v1/candidates/{id}", todoCandidate).with(asUser(family.son())))
                .andExpect(jsonPath("$.evidence[0].spans[0]").value("선물 사 와"));
    }

    // ---- 기존 항목 변경·완료 (S19-2) ----

    @Test
    void 일정_변경_후보는_기존_일정을_가리키고_승인하면_시각만_바꾸고_되돌리면_돌아온다() throws Exception {
        long scheduleId = ScheduleApi.create(mockMvc, family.groupId(), family.dad(),
                ScheduleApi.timed("영화 보기", tomorrow + "T11:00", tomorrow + "T13:00", family.dad(), family.son()));
        long id = change(family.mom(), "11시 말고 12시로 하자", "영화", "11시", "12시");

        mockMvc.perform(get("/api/v1/candidates/{id}", id).with(asUser(family.son())))
                .andExpect(jsonPath("$.candidate.type").value("SCHEDULE"))
                .andExpect(jsonPath("$.candidate.missingFields.length()").value(0))
                .andExpect(jsonPath("$.candidate.fields.title").value("영화 보기"))
                .andExpect(jsonPath("$.candidate.fields.date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.candidate.fields.startTime").value("12:00"))
                .andExpect(jsonPath("$.candidate.fields.endTime").value("14:00"))
                .andExpect(jsonPath("$.candidate.target.entityType").value("SCHEDULE"))
                .andExpect(jsonPath("$.candidate.target.action").value("CHANGE"))
                .andExpect(jsonPath("$.candidate.target.entityId").value(scheduleId))
                .andExpect(jsonPath("$.candidate.target.createdBy").value(family.dad()))
                .andExpect(jsonPath("$.candidate.target.choices[0].createdBy").value(family.dad()))
                .andExpect(jsonPath("$.candidate.target.before.startTime").value("11:00"))
                .andExpect(jsonPath("$.candidate.target.after.startTime").value("12:00"))
                .andExpect(jsonPath("$.candidate.target.choices.length()").value(1))
                .andExpect(jsonPath("$.candidate.target.applied").doesNotExist());

        approve(family.dad(), id, "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("APPROVED"))
                .andExpect(jsonPath("$.candidate.entityType").value("SCHEDULE"))
                .andExpect(jsonPath("$.candidate.entityId").value(scheduleId));
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.son())))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T12:00:00+09:00"))
                .andExpect(jsonPath("$.endsAt").value(tomorrow + "T14:00:00+09:00"))
                .andExpect(jsonPath("$.title").value("영화 보기"))
                .andExpect(jsonPath("$.participantIds.length()").value(2))
                .andExpect(jsonPath("$.source").doesNotExist());
        assertThat(count("schedules WHERE group_id = " + family.groupId())).isEqualTo(1);
        assertThat(lastSystemMessage()).endsWith("님이 '영화 보기' 일정을 12:00으로 바꿨어요.");
        assertThat(feedback(id)).containsExactly("APPROVED:{}");

        undo(family.dad(), id).andExpect(status().isOk()).andExpect(jsonPath("$.candidate.status").value("UNDONE"));
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.son())))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T11:00:00+09:00"))
                .andExpect(jsonPath("$.endsAt").value(tomorrow + "T13:00:00+09:00"));
        assertThat(count("messages WHERE room_id = " + roomId + " AND type = 'SYSTEM' AND deleted_at IS NULL "
                + "AND payload->>'kind' = 'CANDIDATE_APPROVED'")).isZero();
    }

    @Test
    void 남이_등록한_일정은_수정_권한이_없으면_변경_승인이_403이고_후보는_그대로다() throws Exception {
        long scheduleId = ScheduleApi.create(mockMvc, family.groupId(), family.dad(),
                ScheduleApi.timed("영화 보기", tomorrow + "T11:00", tomorrow + "T13:00", family.dad()));
        long id = change(family.son(), "영화 11시 말고 12시로 하자", "영화", "11시", "12시");

        approve(family.son(), id, "{\"version\":0}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration_candidates WHERE id = ?",
                String.class, id)).isEqualTo("PENDING");

        approve(family.mom(), id, "{\"version\":0}").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.dad())))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T12:00:00+09:00"));
    }

    @Test
    void 그사이_일정이_바뀌면_변경_승인과_되돌리기가_409다() throws Exception {
        long scheduleId = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                ScheduleApi.timed("영화 보기", tomorrow + "T11:00", tomorrow + "T13:00", family.mom()));
        long first = change(family.mom(), "영화 11시 말고 12시로 하자", "영화", "11시", "12시");
        long second = change(family.mom(), "아니다 영화 11시 말고 3시로 하자", "영화", "11시", "3시");

        approve(family.mom(), first, "{\"version\":0}").andExpect(status().isOk());
        approve(family.mom(), second, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANDIDATE_TARGET_CHANGED"));

        jdbcTemplate.update("UPDATE schedules SET version = version + 1 WHERE id = ?", scheduleId);
        undo(family.mom(), first).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANDIDATE_TARGET_CHANGED"));
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.startsAt").value(tomorrow + "T12:00:00+09:00"));
    }

    @Test
    void 할_일_완료_후보는_승인하면_완료하고_되돌리면_다시_연다() throws Exception {
        long todoId = todoItem(family.mom(), "세제 사 오기");
        long id = done(family.son(), "세제 사 왔어요", "세제");

        mockMvc.perform(get("/api/v1/candidates/{id}", id).with(asUser(family.mom())))
                .andExpect(jsonPath("$.candidate.type").value("TODO"))
                .andExpect(jsonPath("$.candidate.fields.title").value("세제 사 오기"))
                .andExpect(jsonPath("$.candidate.target.action").value("COMPLETE"))
                .andExpect(jsonPath("$.candidate.target.entityId").value(todoId))
                .andExpect(jsonPath("$.candidate.target.before.done").value(false));

        approve(family.dad(), id, "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.entityType").value("TODO"))
                .andExpect(jsonPath("$.candidate.entityId").value(todoId));
        mockMvc.perform(get("/api/v1/todos/{id}", todoId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.doneBy").value(family.dad()));
        assertThat(count("todos WHERE group_id = " + family.groupId())).isEqualTo(1);
        assertThat(lastSystemMessage()).endsWith("님이 '세제 사 오기' 할 일을 완료했어요.");

        undo(family.dad(), id).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/todos/{id}", todoId).with(asUser(family.mom())))
                .andExpect(jsonPath("$.done").value(false));
    }

    @Test
    void 대상이_여럿이면_골라야_하고_고르면_EDITED로_남긴다() throws Exception {
        long refill = todoItem(family.mom(), "세제 리필 주문");
        todoItem(family.mom(), "세제 사 오기");
        long id = done(family.son(), "세제 샀어", "세제");

        mockMvc.perform(get("/api/v1/candidates/{id}", id).with(asUser(family.mom())))
                .andExpect(jsonPath("$.candidate.missingFields[0]").value("targetId"))
                .andExpect(jsonPath("$.candidate.target.entityId").doesNotExist())
                .andExpect(jsonPath("$.candidate.target.choices.length()").value(2));
        approve(family.mom(), id, "{\"version\":0}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("targetId"));
        approve(family.mom(), id, "{\"version\":0,\"targetId\":999999}").andExpect(status().isBadRequest());

        approve(family.mom(), id, "{\"version\":0,\"targetId\":%d}".formatted(refill)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.entityId").value(refill))
                .andExpect(jsonPath("$.candidate.target.entityId").value(refill))
                .andExpect(jsonPath("$.candidate.target.title").value("세제 리필 주문"));
        assertThat(feedback(id)).containsExactly("EDITED:{targetId}");
    }

    @Test
    void 바꿀_일정이나_끝낼_할_일이_없으면_후보를_만들지_않는다() throws Exception {
        assertThat(change(family.mom(), "11시 말고 12시로 하자", "영화", "11시", "12시")).isEqualTo(-1);
        assertThat(done(family.son(), "세제 샀어", "세제")).isEqualTo(-1);

        todoItem(family.mom(), "세제 사 오기");
        jdbcTemplate.update("UPDATE todos SET done_at = now(), done_by = ? WHERE group_id = ?", family.mom(),
                family.groupId());
        assertThat(done(family.son(), "세제 샀어", "세제")).isEqualTo(-1);
    }

    @Test
    void 다른_그룹의_일정과_할_일은_대상이_되지_않는다() throws Exception {
        Family other = Family.create(mockMvc, this::createUser);
        ScheduleApi.create(mockMvc, other.groupId(), other.mom(),
                ScheduleApi.timed("영화 보기", tomorrow + "T11:00", tomorrow + "T13:00", other.mom()));
        mockMvc.perform(post("/api/v1/groups/{g}/todos", other.groupId()).with(asUser(other.mom()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"세제 사 오기\"}"))
                .andExpect(status().isCreated());

        assertThat(change(family.mom(), "11시 말고 12시로 하자", "영화", "11시", "12시")).isEqualTo(-1);
        assertThat(done(family.son(), "세제 샀어", "세제")).isEqualTo(-1);
    }

    // ---- 무시 ----

    @Test
    void 무시하면_사유를_남기고_같은_말을_다시_해도_새_후보가_없다() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);

        mockMvc.perform(post("/api/v1/candidates/{id}/dismiss", id).with(asUser(family.dad()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"JOKE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("DISMISSED"))
                .andExpect(jsonPath("$.candidate.dismissReason").value("JOKE"));
        assertThat(feedback(id)).containsExactly("DISMISSED:{}:JOKE");

        schedule(family.son(), "내일 11시 할머니 댁 꼭 가요", "할머니 댁 방문", "11시", 0.9);
        assertThat(count("registration_candidates WHERE group_id = " + family.groupId())).isEqualTo(1);
        approve(family.mom(), id, "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("테스트님이 먼저 무시했어요."));
    }

    // ---- 되돌리기 ----

    @Test
    void 승인한_사람은_5분_안에_되돌리고_항목과_시스템_메시지가_지워진다() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        String body = approve(family.dad(), id, "{\"version\":0}").andReturn().getResponse().getContentAsString();
        long scheduleId = ((Number) JsonPath.read(body, "$.candidate.entityId")).longValue();

        undo(family.son(), id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_UNDOABLE"));
        undo(family.dad(), id).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("UNDONE"));

        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.dad())))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE room_id = ? AND type = 'SYSTEM' "
                + "AND payload ->> 'kind' = 'CANDIDATE_APPROVED' AND deleted_at IS NULL", Integer.class, roomId))
                .isZero();
        assertThat(count("item_sources WHERE entity_type = 'SCHEDULE' AND entity_id = " + scheduleId)).isZero();
        assertThat(feedback(id)).containsExactly("APPROVED:{}", "UNDONE:{}");
        undo(family.dad(), id).andExpect(status().isOk());
    }

    @Test
    void 관리자는_남이_승인한_것도_되돌리고_5분이_지나면_못_되돌린다() throws Exception {
        long first = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        long second = todo(family.mom(), "아빠 내일까지 택배 반품 보내줘", "택배 반품", "내일까지", "아빠", 0.9);
        approve(family.dad(), first, "{\"version\":0}").andExpect(status().isOk());
        approve(family.dad(), second, "{\"version\":0}").andExpect(status().isOk());

        undo(family.mom(), first).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE registration_candidates SET decided_at = now() - interval '6 minutes' WHERE id = ?",
                second);
        undo(family.dad(), second).andExpect(status().isForbidden());
    }

    // ---- 권한 ----

    @Test
    void 다른_그룹_사람은_목록은_403_후보_id_로는_404() throws Exception {
        long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
        long outsider = createUser();

        mockMvc.perform(get("/api/v1/groups/{g}/candidates", family.groupId()).with(asUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/candidates/{id}", id).with(asUser(outsider)))
                .andExpect(status().isNotFound());
        approve(outsider, id, "{\"version\":0}").andExpect(status().isNotFound());
        undo(outsider, id).andExpect(status().isNotFound());
    }

    // ---- WebSocket ----

    @Test
    void 후보가_생기고_처리되면_그룹에_알린다() throws Exception {
        try (StompTestClient client = StompTestClient.connect(port, token(family.son()))) {
            BlockingQueue<String> events = client.subscribeGroup(family.groupId());
            client.sync(family.groupId());
            events.clear();

            long id = schedule(family.mom(), "내일 11시에 할머니 댁 가자", "할머니 댁 방문", "11시", 0.9);
            String created = awaitEvent(events, "candidate.created");
            assertThat((Integer) JsonPath.read(created, "$.data.id")).isEqualTo((int) id);
            assertThat((String) JsonPath.read(created, "$.data.surface")).isEqualTo("CARD");

            approve(family.mom(), id, "{\"version\":0}").andExpect(status().isOk());
            String resolved = awaitEvent(events, "candidate.resolved");
            assertThat((String) JsonPath.read(resolved, "$.data.status")).isEqualTo("APPROVED");
            assertThat((String) JsonPath.read(resolved, "$.data.decidedByName")).isEqualTo("테스트");
            assertThat((String) JsonPath.read(resolved, "$.data.entityType")).isEqualTo("SCHEDULE");
        }
    }

    // ---- 도움 함수 ----

    private long schedule(long sender, String body, String title, String time, double confidence) {
        return candidate(sender, body, """
                {"type":"SCHEDULE","fields":{"title":"%s","dateText":"내일","startTimeText":"%s",
                 "locationText":"할머니 댁","attendeeTexts":["가자"]},"confidence":%s,
                 "evidence":[{"messageId":%%d,"spans":["내일 11시"]}]}""".formatted(title, time, confidence));
    }

    private long todo(long sender, String body, String title, String due, String assignee, double confidence) {
        return candidate(sender, body, """
                {"type":"TODO","fields":{"title":"%s","dueText":"%s"%s},"confidence":%s,
                 "evidence":[{"messageId":%%d}]}""".formatted(title, due,
                assignee == null ? "" : ",\"assigneeTexts\":[\"" + assignee + "\"]", confidence));
    }

    private long place(long sender, String name, String opinion) {
        return candidate(sender, name + " 맛있더라", """
                {"type":"PLACE","fields":{"name":"%s"%s},"confidence":0.85,"evidence":[{"messageId":%%d}]}"""
                .formatted(name, opinion == null ? "" : ",\"opinion\":\"" + opinion + "\""));
    }

    private long change(long sender, String body, String title, String fromTime, String time) {
        return candidate(sender, body, """
                {"type":"SCHEDULE_CHANGE","fields":{"title":"%s","fromTimeText":"%s","startTimeText":"%s"},
                 "confidence":0.85,"evidence":[{"messageId":%%d,"spans":["%s"]}]}""".formatted(title, fromTime, time,
                time));
    }

    private long done(long sender, String body, String title) {
        return candidate(sender, body, """
                {"type":"TODO_DONE","fields":{"title":"%s"},"confidence":0.9,"evidence":[{"messageId":%%d}]}"""
                .formatted(title));
    }

    private long todoItem(long userId, String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups/{g}/todos", family.groupId()).with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String lastSystemMessage() {
        return jdbcTemplate.queryForObject("SELECT body FROM messages WHERE room_id = ? AND type = 'SYSTEM' "
                + "ORDER BY seq DESC LIMIT 1", String.class, roomId);
    }

    /** 메시지를 보내고, 그 메시지 하나가 분석 구간인 워커 결과로 후보를 만든다. @return 후보 id (안 만들어졌으면 -1) */
    private long candidate(long sender, String body, String candidateJson) {
        long messageId = send(family.groupId(), sender, body).id();
        long seq = lastSeq();
        long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs)
                VALUES (?, ?, ?, ?, 'RULE', 1, '{}') RETURNING id
                """, Long.class, family.groupId(), roomId, seq - 1, seq);
        Map<String, String> result = new HashMap<>();
        result.put("jobId", Long.toString(jobId));
        result.put("status", "DONE");
        result.put("candidates", "[" + candidateJson.formatted(messageId) + "]");
        result.put("modelVersion", "fake");
        result.put("promptVersion", "none");
        handler.apply(result);
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM registration_candidates WHERE job_id = ?",
                Long.class, jobId);
        return ids.isEmpty() ? -1 : ids.get(0);
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/{g}/candidates?status=PENDING" + query, family.groupId())
                .with(asUser(family.son())));
    }

    private ResultActions approve(long userId, long id, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/candidates/{id}/approve", id).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions undo(long userId, long id) throws Exception {
        return mockMvc.perform(post("/api/v1/candidates/{id}/undo", id).with(asUser(userId)));
    }

    /** "ACTION:{고친 항목}" 또는 "ACTION:{}:사유" (처리 순) */
    private List<String> feedback(long candidateId) {
        return jdbcTemplate.queryForList("""
                SELECT action || ':' || edited_fields::text || coalesce(':' || dismiss_reason, '')
                FROM suggestion_feedback WHERE candidate_id = ? ORDER BY id
                """, String.class, candidateId);
    }

    private long lastMessageId() {
        return jdbcTemplate.queryForObject("SELECT id FROM messages WHERE room_id = ? ORDER BY seq DESC LIMIT 1",
                Long.class, roomId);
    }

    private long lastSeq() {
        return roomLastSeq(family.groupId());
    }

    private int count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    private static String awaitEvent(BlockingQueue<String> events, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + StompTestClient.WAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            String event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && event.contains("\"type\":\"" + type + "\"")) {
                return event;
            }
        }
        throw new AssertionError(type + " 이벤트가 오지 않았습니다");
    }
}
