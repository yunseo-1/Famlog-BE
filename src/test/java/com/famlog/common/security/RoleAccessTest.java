package com.famlog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.Family;
import com.famlog.support.PlaceApi;
import com.famlog.support.ScheduleApi;

/**
 * SEC-T2 (사양서 9.9, 2장 권한표): MEMBER 가 관리자 권한 그룹 API 나 다른 사람이 만든 항목을 바꾸면 403, 앱 사용자 토큰으로 관리자
 * 도구를 부르면 401. 같은 요청을 그룹장이 보내면 통과해 403 이 권한 때문임을 확인한다
 */
class RoleAccessTest extends ChatIntegrationTest {

    private record Case(HttpMethod method, String uri, String body) {
    }

    @Test
    void MEMBER가_관리자_권한_API를_부르면_403이다() throws Exception {
        Family family = Family.create(mockMvc, this::createUser);
        long g = family.groupId();
        long schedule = ScheduleApi.create(mockMvc, g, family.mom(), ScheduleApi.timed("병원", "2026-12-01T10:00",
                "2026-12-01T11:00", family.mom()));
        long todo = jdbcTemplate.queryForObject("INSERT INTO todos (group_id, title, created_by) VALUES (?, '우유', ?) "
                + "RETURNING id", Long.class, g, family.mom());
        long place = jdbcTemplate.queryForObject("""
                INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, shared_by)
                VALUES (?, ?, '단골 식당', 'RESTAURANT', '서울', '서울', 37.5, 127.0, ?) RETURNING id""", Long.class, g,
                PlaceApi.newKakaoId(), family.mom());
        long alias = jdbcTemplate.queryForObject("INSERT INTO place_aliases (group_id, alias, place_id, created_by) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, g, "단골-" + UUID.randomUUID().toString()
                .substring(0, 8), place, family.mom());

        // 관리자 권한 (OWNER·ADMIN)
        List<Case> adminOnly = List.of(
                new Case(HttpMethod.PATCH, "/api/v1/groups/%d/members/%d".formatted(g, family.dad()),
                        "{\"role\":\"ADMIN\"}"),
                new Case(HttpMethod.POST, "/api/v1/groups/%d/invites".formatted(g), null),
                new Case(HttpMethod.PATCH, "/api/v1/groups/%d/settings".formatted(g),
                        "{\"aiAnalysisEnabled\":false}"),
                new Case(HttpMethod.DELETE, "/api/v1/groups/%d/members/%d".formatted(g, family.dad()), null),
                new Case(HttpMethod.DELETE, "/api/v1/groups/%d".formatted(g), null));
        // 다른 사람이 만든 항목 (만든 사람·ADMIN 이상만)
        List<Case> ownerOfItem = List.of(
                new Case(HttpMethod.PATCH, "/api/v1/schedules/" + schedule, ScheduleApi.with(ScheduleApi.timed("바꿈",
                        "2026-12-01T12:00", "2026-12-01T13:00", family.mom()), "\"version\":0")),
                new Case(HttpMethod.PATCH, "/api/v1/todos/" + todo, "{\"version\":0,\"title\":\"바꿈\"}"),
                new Case(HttpMethod.PATCH, "/api/v1/places/" + place, "{\"oneLineReview\":\"바꿈\"}"),
                new Case(HttpMethod.DELETE, "/api/v1/groups/%d/place-aliases/%d".formatted(g, alias), null),
                new Case(HttpMethod.DELETE, "/api/v1/schedules/" + schedule, null),
                new Case(HttpMethod.DELETE, "/api/v1/todos/" + todo, null),
                new Case(HttpMethod.DELETE, "/api/v1/places/" + place, null));

        List<String> failures = new ArrayList<>();
        for (Case c : concat(adminOnly, ownerOfItem)) {
            int status = perform(c, family.son());
            if (status != 403) {
                failures.add("%s %s → %d".formatted(c.method(), c.uri(), status));
            }
        }
        assertThat(failures).as("MEMBER 가 403 이 아니었던 요청").isEmpty();

        // 같은 요청을 그룹장이 보내면 통과한다 (바꾸기만, 지우기는 위에서 이미 확인)
        assertThat(perform(adminOnly.get(0), family.mom())).isEqualTo(200);
        assertThat(perform(adminOnly.get(2), family.mom())).isEqualTo(200);
        assertThat(perform(ownerOfItem.get(1), family.mom())).isEqualTo(200);
    }

    @Test
    void 앱_사용자_토큰으로_관리자_도구를_부르면_401이다() throws Exception {
        long user = createUser();
        for (String path : List.of("/admin/api/reports", "/admin/api/ops-summary", "/admin/api/audit-logs")) {
            assertThat(mockMvc.perform(get(path).with(asUser(user))).andReturn().getResponse().getStatus())
                    .as(path).isEqualTo(401);
        }
    }

    private int perform(Case c, long userId) throws Exception {
        MockHttpServletRequestBuilder builder = request(c.method(), c.uri()).with(asUser(userId));
        if (c.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(c.body());
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static List<Case> concat(List<Case> a, List<Case> b) {
        List<Case> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
