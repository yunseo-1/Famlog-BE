package com.famlog.schedule.service;

import static com.famlog.support.ScheduleApi.allDay;
import static com.famlog.support.ScheduleApi.timed;
import static com.famlog.support.ScheduleApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.famlog.common.time.FamlogTime;
import com.famlog.notification.push.PushMessage;
import com.famlog.support.Family;
import com.famlog.support.PushIntegrationTest;
import com.famlog.support.ScheduleApi;

/**
 * 일정 리마인드 (사양서 4.3 SCH-03): 참여 구성원에게 전날 20:00 + 1시간 전, 회차·종류마다 한 번.
 * 스케줄러는 꺼 두고 시각을 정해 {@link ReminderService#sendDue} 를 부른다. 테스트끼리 같은 시각을 쓰지 않게 날짜를 무작위로 고른다.
 */
class ReminderTest extends PushIntegrationTest {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private Clock clock;

    private Family family;
    private String momToken;
    private String dadToken;
    private String sonToken;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        momToken = deviceOf(family.mom());
        dadToken = deviceOf(family.dad());
        sonToken = deviceOf(family.son());
        day = LocalDate.now(FamlogTime.DISPLAY_ZONE).plusDays(ThreadLocalRandom.current().nextInt(400, 20_000));
    }

    @Test
    void 전날_20시와_1시간_전에_참여자에게만_보낸다() throws Exception {
        long id = create(timed("할머니 병원", at(day, "10:00"), at(day, "11:00"), family.mom(), family.dad()));

        reminderService.sendDue(kst(day.minusDays(1), "20:00"));
        PushMessage evening = pushSender.await(momToken, reminder(id, "PREV_DAY_20"));
        assertThat(evening.data()).containsEntry("type", "SCHEDULE_REMINDER")
                .containsEntry("channel", "SCHEDULE")
                .containsEntry("groupId", Long.toString(family.groupId()))
                .containsEntry("groupName", "우리 가족")
                .containsEntry("title", "할머니 병원")
                .containsEntry("startsAt", day + "T10:00:00+09:00")
                .containsEntry("allDay", "false")
                .containsKeys("sentAt", "silent");
        assertThat(pushSender.batchOf(evening)).extracting(PushMessage::token)
                .containsExactlyInAnyOrder(momToken, dadToken);

        reminderService.sendDue(kst(day, "08:59"));
        assertThat(sentCount(id, "HOUR_1")).isZero();
        reminderService.sendDue(kst(day, "09:00"));
        pushSender.await(dadToken, reminder(id, "HOUR_1"));
        assertThat(pushSender.to(sonToken, data -> "SCHEDULE_REMINDER".equals(data.get("type")))).isEmpty();
    }

    @Test
    void 같은_회차는_여러_번_실행해도_한_번만_보낸다() throws Exception {
        long id = create(timed("장보기", at(day, "10:00"), at(day, "11:00"), family.mom()));

        reminderService.sendDue(kst(day.minusDays(1), "20:00"));
        reminderService.sendDue(kst(day.minusDays(1), "20:00"));
        reminderService.sendDue(kst(day.minusDays(1), "20:03"));
        pushSender.await(momToken, reminder(id, "PREV_DAY_20"));
        Thread.sleep(300);

        assertThat(pushSender.to(momToken, reminder(id, "PREV_DAY_20"))).hasSize(1);
        assertThat(sentCount(id, "PREV_DAY_20")).isEqualTo(1);
    }

    @Test
    void 지난_5분까지는_놓친_알림을_보내고_그보다_오래되면_보내지_않는다() throws Exception {
        long late = create(with(timed("늦게 확인", at(day, "10:00"), at(day, "11:00"), family.mom()),
                "\"reminders\":[\"HOUR_1\"]"));
        LocalDate otherDay = day.plusDays(1);
        long tooLate = create(with(timed("너무 늦음", at(otherDay, "10:00"), at(otherDay, "11:00"), family.mom()),
                "\"reminders\":[\"HOUR_1\"]"));

        reminderService.sendDue(kst(day, "09:04"));
        reminderService.sendDue(kst(otherDay, "09:06"));

        pushSender.await(momToken, reminder(late, "HOUR_1"));
        assertThat(sentCount(tooLate, "HOUR_1")).isZero();
    }

    @Test
    void 나간_사람과_그룹_알림을_끈_사람은_빼고_방해_금지면_무음_미리보기_숨김이면_제목이_없다() throws Exception {
        long id = create(timed("가족 사진", at(day, "10:00"), at(day, "11:00"), family.mom(), family.dad(),
                family.son()));
        mockMvc.perform(post("/api/v1/groups/{g}/leave", family.groupId()).with(asUser(family.son())))
                .andExpect(status().isNoContent());
        patchJson("/api/v1/groups/" + family.groupId() + "/members/me/preferences", family.dad(),
                "{\"notifMuted\":true}");
        // 방해 금지 시간은 지금(서울) 앞뒤 1시간 → 지금 보내는 푸시는 무음
        LocalTime now = LocalTime.now(clock.withZone(FamlogTime.DISPLAY_ZONE)).truncatedTo(ChronoUnit.MINUTES);
        patchJson("/api/v1/users/me/notification-settings", family.mom(), """
                {"previewHidden":true,"quietHoursEnabled":true,"quietStart":"%s","quietEnd":"%s"}"""
                .formatted(now.minusHours(1), now.plusHours(1)));

        reminderService.sendDue(kst(day.minusDays(1), "20:00"));

        PushMessage message = pushSender.await(momToken, reminder(id, "PREV_DAY_20"));
        assertThat(message.data()).containsEntry("silent", "true").doesNotContainKey("title");
        assertThat(pushSender.batchOf(message)).extracting(PushMessage::token).containsExactly(momToken);
    }

    @Test
    void 반복_일정은_회차마다_보내고_종일_일정은_전날_20시에_보낸다() throws Exception {
        long weekly = create(with(timed("피아노", at(day, "16:00"), at(day, "17:00"), family.son()),
                "\"rrule\":\"FREQ=WEEKLY\""));
        LocalDate tripDay = day.plusDays(3);
        long trip = create(allDay("여행", tripDay.toString(), tripDay.plusDays(1).toString(), family.son()));

        reminderService.sendDue(kst(day.plusWeeks(2), "15:00"));
        assertThat(pushSender.await(sonToken, reminder(weekly, "HOUR_1")).data())
                .containsEntry("startsAt", day.plusWeeks(2) + "T16:00:00+09:00");

        reminderService.sendDue(kst(tripDay.minusDays(1), "20:00"));
        assertThat(pushSender.await(sonToken, reminder(trip, "PREV_DAY_20")).data())
                .containsEntry("allDay", "true")
                .containsEntry("startsAt", tripDay + "T00:00:00+09:00");
    }

    @Test
    void 알림_시각이_등록보다_앞이면_보내지_않는다() throws Exception {
        // 지금 등록한 일정: 59분 뒤 시작이면 1시간 전 알림은 1분 전이라 이미 지났다
        LocalDateTime base = LocalDateTime.now(clock.withZone(FamlogTime.DISPLAY_ZONE)).truncatedTo(ChronoUnit.MINUTES);
        long missed = create(with(timed("곧 시작", base.plusMinutes(59).format(MINUTE),
                base.plusMinutes(90).format(MINUTE), family.dad()), "\"reminders\":[\"HOUR_1\"]"));
        long upcoming = create(with(timed("조금 뒤", base.plusMinutes(63).format(MINUTE),
                base.plusMinutes(90).format(MINUTE), family.dad()), "\"reminders\":[\"HOUR_1\"]"));

        // 지난 5분 안이라 시각만 보면 둘 다 보낼 때다
        reminderService.sendDue(base.plusMinutes(3).atZone(FamlogTime.DISPLAY_ZONE).toInstant());

        pushSender.await(dadToken, reminder(upcoming, "HOUR_1"));
        assertThat(sentCount(missed, "HOUR_1")).isZero();
    }

    @Test
    void 지운_일정은_보내지_않는다() throws Exception {
        long id = create(timed("취소된 약속", at(day, "10:00"), at(day, "11:00"), family.mom()));
        mockMvc.perform(delete("/api/v1/schedules/{id}", id).with(asUser(family.mom())))
                .andExpect(status().isNoContent());

        reminderService.sendDue(kst(day.minusDays(1), "20:00"));
        Thread.sleep(300);

        assertThat(pushSender.to(momToken, reminder(id, "PREV_DAY_20"))).isEmpty();
    }

    private long create(String body) throws Exception {
        return ScheduleApi.create(mockMvc, family.groupId(), family.mom(), body);
    }

    private void patchJson(String path, long userId, String body) throws Exception {
        mockMvc.perform(patch(path).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long sentCount(long scheduleId, String kind) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schedule_reminder_sends WHERE schedule_id = ? AND kind = ?", Long.class,
                scheduleId, kind);
    }

    private static Predicate<Map<String, String>> reminder(long scheduleId, String kind) {
        return data -> "SCHEDULE_REMINDER".equals(data.get("type"))
                && Long.toString(scheduleId).equals(data.get("scheduleId")) && kind.equals(data.get("reminder"));
    }

    private static String at(LocalDate date, String time) {
        return date + "T" + time;
    }

    private static Instant kst(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(FamlogTime.DISPLAY_ZONE).toInstant();
    }
}
