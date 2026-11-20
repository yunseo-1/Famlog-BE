package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.normalize.TimeResult;
import com.famlog.candidate.service.CandidateBuilder.Built;
import com.famlog.candidate.service.CandidateTargets.Items;
import com.famlog.candidate.service.CandidateTargets.Query;
import com.famlog.candidate.service.CandidateTargets.ScheduleItem;
import com.famlog.candidate.service.CandidateTargets.TodoItem;

/** 변경·완료 후보의 대상 찾기 (S19-2). 기준 시각 2026-11-12(목) 10:00 KST */
class CandidateTargetsTest {

    private static final Instant NOW = at("2026-11-12T10:00");
    private static final LocalDate SAT = LocalDate.of(2026, 11, 14);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 15);

    @Test
    void 바꾸기_전_시각과_핵심어가_맞는_일정을_찾아_길이를_그대로_옮긴다() {
        Items items = schedules(timed(1, "영화 보기", "2026-11-14T11:00", "2026-11-14T13:00"),
                timed(2, "치과", "2026-11-14T15:00", "2026-11-14T16:00"));

        Built built = resolve(change("영화", null, time(11, true), null, time(12, true)), items).orElseThrow();

        assertThat(built.missing()).isEmpty();
        assertThat(built.fields()).containsEntry("title", "영화 보기").containsEntry("date", "2026-11-14")
                .containsEntry("startTime", "12:00").containsEntry("endTime", "14:00").containsEntry("allDay", false);
        assertThat(built.target()).containsEntry("entityType", "SCHEDULE").containsEntry("action", "CHANGE")
                .containsEntry("entityId", 1L).containsEntry("createdBy", 7L);
        assertThat(built.inferred()).containsExactly("startTime.ampm");
        assertThat(built.expiresAt()).isEqualTo(at("2026-11-14T11:00"));
        assertThat(built.dedupKey()).isEqualTo("SCHEDULE|CHANGE|1||12:00");
    }

    @Test
    void 오전_오후를_추정한_시각은_바꾸기_전_시각에_가까운_쪽이다() {
        Items items = schedules(timed(1, "저녁 약속", "2026-11-14T18:00", "2026-11-14T20:00"));

        Built built = resolve(change(null, null, time(6, true), null, new TimeResult(LocalTime.of(7, 0), true,
                false)), items).orElseThrow();

        assertThat(built.fields()).containsEntry("startTime", "19:00").containsEntry("endTime", "21:00");
    }

    @Test
    void 날짜만_바꾸면_시각은_그대로이고_종일_일정은_날짜만_옮긴다() {
        Items items = schedules(timed(1, "할머니 댁 방문", "2026-11-14T11:00", "2026-11-14T13:00"),
                allDay(2, "김장", SAT, SAT.plusDays(2)));

        assertThat(resolve(change("할머니", SAT, null, SUN, null), items).orElseThrow().fields())
                .containsEntry("date", "2026-11-15").containsEntry("startTime", "11:00");
        Built kimjang = resolve(change("김장", SAT, null, SUN, null), items).orElseThrow();
        assertThat(kimjang.fields()).containsEntry("date", "2026-11-15").containsEntry("allDay", true);
        assertThat(((Map<?, ?>) kimjang.target().get("after")).get("endsAt"))
                .isEqualTo(at("2026-11-17T00:00").toString());
    }

    @Test
    void 요일만_말하면_대상_일정이_있는_주의_그_요일이다() {
        Items items = schedules(timed(1, "아빠 회식", "2026-11-18T19:00", "2026-11-18T22:00"));
        Query thursday = new Query(CandidateTargets.CHANGE, "회식", LocalDate.of(2026, 11, 18), null,
                LocalDate.of(2026, 11, 12), null, List.of(), java.time.DayOfWeek.THURSDAY);

        assertThat(resolve(thursday, items).orElseThrow().fields()).containsEntry("date", "2026-11-19")
                .containsEntry("startTime", "19:00");
    }

    @Test
    void 종일_일정에_시각을_정하면_그_시각의_시간_일정이_된다() {
        Items items = schedules(allDay(1, "김장", SAT, SAT.plusDays(1)));

        Built built = resolve(change("김장", null, null, null, time(14, false)), items).orElseThrow();

        assertThat(built.fields()).containsEntry("allDay", false).containsEntry("startTime", "14:00")
                .containsEntry("endTime", "14:00");
    }

    @Test
    void 아무것도_말하지_않았으면_최근_대화의_일정이고_여럿이면_최근_대화를_먼저_본다() {
        List<ScheduleItem> list = List.of(timed(1, "영화", "2026-11-14T11:00", "2026-11-14T13:00"),
                timed(2, "영화", "2026-11-21T11:00", "2026-11-21T13:00"));

        assertThat(resolve(change(null, null, null, null, time(12, true)), new Items(list, List.of(), Set.of(2L)))
                .orElseThrow().target()).containsEntry("entityId", 2L);
        assertThat(resolve(change(null, null, null, null, time(12, true)), new Items(list, List.of(), Set.of())))
                .isEmpty();
        assertThat(resolve(change("영화", null, null, null, time(12, true)), new Items(list, List.of(), Set.of(1L)))
                .orElseThrow().target()).containsEntry("entityId", 1L);
    }

    @Test
    void 여럿이면_골라야_하고_다섯_개를_넘거나_바뀌는_것이_없거나_지난_시각이면_만들지_않는다() {
        Items two = schedules(timed(1, "영화", "2026-11-14T11:00", "2026-11-14T13:00"),
                timed(2, "영화", "2026-11-21T11:00", "2026-11-21T13:00"));
        Built built = resolve(change("영화", null, null, null, time(12, true)), two).orElseThrow();
        assertThat(built.missing()).containsExactly("targetId");
        assertThat(built.target()).containsEntry("entityId", null);
        assertThat((List<?>) built.target().get("choices")).hasSize(2);
        assertThat(built.dedupKey()).isEqualTo("SCHEDULE|CHANGE|1,2||12:00");

        Items six = schedules(java.util.stream.LongStream.rangeClosed(1, 6)
                .mapToObj(i -> timed(i, "영화", "2026-11-%dT11:00".formatted(13 + i), "2026-11-%dT13:00"
                        .formatted(13 + i))).toArray(ScheduleItem[]::new));
        assertThat(resolve(change("영화", null, null, null, time(12, true)), six)).isEmpty();

        Items one = schedules(timed(1, "영화", "2026-11-14T11:00", "2026-11-14T13:00"));
        assertThat(resolve(change("영화", null, null, null, time(11, true)), one)).isEmpty();
        assertThat(resolve(change("영화", null, null, LocalDate.of(2026, 11, 11), null), one)).isEmpty();
    }

    @Test
    void 바꾸기_전_날짜나_시각이_다르거나_핵심어가_없으면_찾지_않는다() {
        Items items = schedules(timed(1, "영화 보기", "2026-11-14T11:00", "2026-11-14T13:00"));

        assertThat(resolve(change("영화", SUN, null, null, time(12, true)), items)).isEmpty();
        assertThat(resolve(change("영화", null, time(3, true), null, time(12, true)), items)).isEmpty();
        assertThat(resolve(change("치과", null, null, null, time(12, true)), items)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 할_일은_핵심어_조각이_모두_제목에_있는_것() {
        Items items = new Items(List.of(), List.of(new TodoItem(1, "자동차 점검 맡기기", 7L, SAT),
                new TodoItem(2, "세제 사 오기", null, null)), Set.of());

        Built built = resolve(done("자동차 점검"), items).orElseThrow();
        assertThat(built.fields()).containsEntry("title", "자동차 점검 맡기기");
        assertThat(built.target()).containsEntry("action", "COMPLETE").containsEntry("entityId", 1L);
        assertThat((Map<String, Object>) built.target().get("before")).containsEntry("assigneeId", 7L)
                .containsEntry("dueDate", "2026-11-14");
        assertThat(built.dedupKey()).isEqualTo("TODO|COMPLETE|1|");
        assertThat(resolve(done("세제"), items).orElseThrow().target()).containsEntry("entityId", 2L);
        assertThat(resolve(done("자동차 세차"), items)).isEmpty();
        assertThat(resolve(done("세제 구매"), items).orElseThrow().target()).containsEntry("entityId", 2L);
        assertThat(CandidateTargets.keywordMatches("치과 예약", "치과")).isTrue();
        assertThat(CandidateTargets.keywordMatches("관리비 납부", "관리비 내기")).isTrue();
        assertThat(CandidateTargets.keywordMatches("예약", "치과")).isFalse();
    }

    private static Optional<Built> resolve(Query query, Items items) {
        CandidateType type = CandidateTargets.CHANGE.equals(query.action()) ? CandidateType.SCHEDULE
                : CandidateType.TODO;
        Built built = new Built(type, Map.of(), List.of(), List.of(), 0.9, "", NOW.plus(CandidateBuilder.DEFAULT_TTL),
                query, null);
        return CandidateTargets.resolve(built, items, NOW);
    }

    private static Query change(String keyword, LocalDate fromDate, TimeResult fromTime, LocalDate date,
            TimeResult time) {
        return new Query(CandidateTargets.CHANGE, keyword, fromDate, fromTime, date, time,
                CandidateTargets.inferredOf(time, false), null);
    }

    private static Query done(String keyword) {
        return new Query(CandidateTargets.COMPLETE, keyword, null, null, null, null, List.of(), null);
    }

    /** "11시"처럼 오전·오후를 추정했으면 inferred=true (TimeNormalizer 규칙: 1~6시 오후, 7~12시 그대로) */
    private static TimeResult time(int hour, boolean inferred) {
        int h = inferred && hour <= 6 ? hour + 12 : hour;
        return new TimeResult(LocalTime.of(h, 0), inferred, false);
    }

    private static Items schedules(ScheduleItem... items) {
        return new Items(List.of(items), List.of(), Set.of());
    }

    private static ScheduleItem timed(long id, String title, String start, String end) {
        return new ScheduleItem(id, title, at(start), at(end), false, 7);
    }

    private static ScheduleItem allDay(long id, String title, LocalDate first, LocalDate endExclusive) {
        return new ScheduleItem(id, title, at(first + "T00:00"), at(endExclusive + "T00:00"), true, 7);
    }

    private static Instant at(String seoulLocal) {
        return OffsetDateTime.parse(seoulLocal + ":00+09:00").toInstant();
    }
}
