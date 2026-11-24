package com.famlog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.famlog.chat.domain.MessageType;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.PlaceApi;
import com.famlog.support.PushIntegrationTest;
import com.famlog.support.ScheduleApi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC-T1 (사양서 9.9, 9.3): 엔드포인트 목록(/v3/api-docs)에서 요청을 만들어, 다른 그룹 사용자가 우리 가족 데이터에 닿지 못하는지
 * 모든 API 에 대해 확인한다.
 * <ul>
 * <li>그룹 경로(/groups/{groupId}/...)에 비구성원 → 403</li>
 * <li>다른 그룹 리소스 id 로 직접 요청(/schedules/{id}, 내 그룹 경로 + 남의 리소스 id 등) → 404 (존재 숨김)</li>
 * </ul>
 * 새 API 의 경로 파라미터가 아래 표에 없으면 실패한다: 표에 넣거나 리소스와 상관없으면 {@link #NOT_RESOURCE}에 이유와 함께 넣는다.
 * 본문으로 그룹·리소스 id 를 받는 API 는 {@link #본문의_다른_그룹_id도_거부한다}에서 따로 확인한다
 */
class CrossGroupAccessTest extends PushIntegrationTest {

    private static final Pattern PARAM = Pattern.compile("\\{(\\w+)}");
    /** 리소스 id 가 아닌 경로 파라미터 (값 고정) */
    private static final Map<String, String> NOT_RESOURCE = Map.of(
            "provider", "kakao", // 로그인 제공자
            "kind", "WANT", // 장소 표시 종류 (placeId 와 함께)
            "type", "LOCATION"); // 동의 종류 (내 것만)
    /**
     * 본문 검증(400)이 권한 검사보다 먼저 끝나지 않도록 형식에 맞는 본문을 보낸다 (없으면 {}).
     * 본문 검증 400 은 리소스가 있든 없든 같아 존재를 드러내지 않지만, 권한 검사까지 닿아야 이 테스트가 의미가 있다
     */
    private static final Map<String, String> BODIES = Map.of(
            "POST /api/v1/candidates/{candidateId}/approve", "{\"version\":0}",
            "POST /api/v1/candidates/{candidateId}/dismiss", "{\"version\":0}",
            "PATCH /api/v1/groups/{groupId}/members/{userId}", "{\"role\":\"ADMIN\"}",
            "POST /api/v1/messages/{messageId}/reactions", "{\"emoji\":\"LIKE\"}",
            "PATCH /api/v1/schedules/{scheduleId}", ScheduleApi.with(ScheduleApi.timed("바꾼 일정",
                    "2026-12-01T12:00", "2026-12-01T13:00", 1L), "\"version\":0"), // 참여자 검사는 권한 다음
            "PATCH /api/v1/todos/{todoId}", "{\"version\":0,\"title\":\"바꾼 할 일\"}");
    /** 관리자 API(토큰 필터, AdminTokenFilterTest·AdminApiTest)와 오류 경로는 대상이 아니다 */
    private static final List<String> EXCLUDED_PREFIXES = List.of("/admin/", "/error");

    @Autowired
    JsonMapper jsonMapper;

    private Family family;
    private long outsider;
    private long outsiderGroup;
    /** 우리 가족(family) 리소스 id. 바깥 사람은 하나도 볼 수 없어야 한다 */
    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        outsider = createUser();
        outsiderGroup = GroupApi.createGroup(mockMvc, outsider, "다른 가족");
        long group = family.groupId();
        long room = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class, group);

        ids.put("groupId", group);
        ids.put("userId", family.dad());
        ids.put("messageId", send(group, family.mom(), "우리끼리 이야기").id());
        ids.put("scheduleId", ScheduleApi.create(mockMvc, group, family.mom(),
                ScheduleApi.timed("병원", "2026-12-01T10:00", "2026-12-01T11:00", family.mom())));
        ids.put("todoId", jdbcTemplate.queryForObject("INSERT INTO todos (group_id, title, created_by) "
                + "VALUES (?, '우유', ?) RETURNING id", Long.class, group, family.mom()));
        long place = jdbcTemplate.queryForObject("""
                INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, shared_by)
                VALUES (?, ?, '단골 식당', 'RESTAURANT', '서울', '서울', 37.5, 127.0, ?) RETURNING id""", Long.class,
                group, PlaceApi.newKakaoId(), family.mom());
        ids.put("placeId", place);
        ids.put("aliasId", jdbcTemplate.queryForObject("INSERT INTO place_aliases (group_id, alias, place_id, "
                + "created_by) VALUES (?, '단골', ?, ?) RETURNING id", Long.class, group, place, family.mom()));
        long media = jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, media_type, mime, size, storage_key, status,
                    upload_expires_at) VALUES (?, ?, 'IMAGE', 'image/jpeg', 1, ?, 'READY', now()) RETURNING id""",
                Long.class, group, family.mom(), "test/" + UUID.randomUUID());
        sendMedia(group, family.mom(), MessageType.IMAGE, List.of(media));
        ids.put("mediaId", media);
        long job = jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs, status)
                VALUES (?, ?, 0, 1, 'RULE', 1, '[]'::jsonb, 'DONE') RETURNING id""", Long.class, group, room);
        ids.put("candidateId", jdbcTemplate.queryForObject("""
                INSERT INTO registration_candidates (group_id, room_id, job_id, type, fields, confidence, dedup_key,
                    expires_at) VALUES (?, ?, ?, 'TODO', '{"title":"우유"}'::jsonb, 0.9, ?, now() + interval '7 days')
                RETURNING id""", Long.class, group, room, job, UUID.randomUUID().toString()));
        ids.put("exportId", jdbcTemplate.queryForObject("INSERT INTO data_export_requests (user_id, requested_at) "
                + "VALUES (?, now()) RETURNING id", Long.class, family.mom()));
        ids.put("sessionId", createSession(family.mom()));
    }

    @Test
    void 엔드포인트_목록의_모든_API에서_다른_그룹_사용자는_403_또는_404다() throws Exception {
        JsonNode paths = jsonMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString()).get("paths");
        RequestPostProcessor asOutsider = asSession(outsider, createSession(outsider));
        List<String> failures = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, JsonNode> entry : paths.properties()) {
            String path = entry.getKey();
            if (EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)) {
                continue;
            }
            List<String> params = params(path);
            List<String> resources = params.stream().filter(p -> !NOT_RESOURCE.containsKey(p)).toList();
            unmapped.addAll(resources.stream().filter(p -> !ids.containsKey(p)).map(p -> path + " {" + p + "}")
                    .toList());
            if (resources.isEmpty() || !unmapped.isEmpty()) {
                continue; // 리소스를 가리키지 않는 경로 (내 것, 목록, 로그인 등)
            }
            for (String method : entry.getValue().propertyNames()) {
                HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
                // ① 우리 가족 id 그대로: 그룹 경로면 403, 리소스면 404
                int expected = params.contains("groupId") ? 403 : 404;
                checked += check(failures, httpMethod, path, fill(path, ids), asOutsider, Set.of(expected));
                // ② 내 그룹 경로 + 우리 가족 리소스 id: 404 (그룹은 통과해도 리소스는 숨긴다)
                if (params.contains("groupId") && resources.size() > 1) {
                    Map<String, Long> mixed = new LinkedHashMap<>(ids);
                    mixed.put("groupId", outsiderGroup);
                    checked += check(failures, httpMethod, path, fill(path, mixed), asOutsider, Set.of(404));
                }
            }
        }

        assertThat(unmapped).as("새 경로 파라미터: 리소스 id 표(ids)나 NOT_RESOURCE 에 넣어 주세요").isEmpty();
        assertThat(failures).as("다른 그룹 사용자가 닿은 API").isEmpty();
        assertThat(checked).as("확인한 요청 수").isGreaterThan(40);
    }

    @Test
    void 본문의_다른_그룹_id도_거부한다() throws Exception {
        RequestPostProcessor asOutsider = asUser(outsider);
        List<String> failures = new ArrayList<>();
        String group = Long.toString(family.groupId());
        check(failures, HttpMethod.PUT, "/api/v1/users/me/current-group", "/api/v1/users/me/current-group",
                asOutsider, Set.of(403, 404), "{\"groupId\":" + group + "}");
        check(failures, HttpMethod.POST, "/api/v1/media/upload-urls", "/api/v1/media/upload-urls", asOutsider,
                Set.of(403, 404), """
                        {"groupId":%s,"items":[{"mimeType":"image/jpeg","size":10}]}""".formatted(group));
        check(failures, HttpMethod.POST, "/api/v1/reports", "/api/v1/reports", asOutsider, Set.of(403, 404),
                "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(ids.get("messageId")));
        check(failures, HttpMethod.POST, "/api/v1/candidates/{candidateId}/approve",
                "/api/v1/candidates/" + ids.get("candidateId") + "/approve", asOutsider, Set.of(404),
                "{\"version\":0,\"targetId\":%d}".formatted(ids.get("scheduleId")));
        assertThat(failures).isEmpty();
    }

    private int check(List<String> failures, HttpMethod method, String template, String uri,
            RequestPostProcessor user, Set<Integer> expected) throws Exception {
        return check(failures, method, template, uri, user, expected,
                BODIES.getOrDefault(method + " " + template, "{}"));
    }

    private int check(List<String> failures, HttpMethod method, String template, String uri,
            RequestPostProcessor user, Set<Integer> expected, String body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, uri).with(user);
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        int status = mockMvc.perform(builder).andReturn().getResponse().getStatus();
        if (!expected.contains(status)) {
            failures.add("%s %s → %d (기대 %s)".formatted(method, template, status, expected));
        }
        return 1;
    }

    private static List<String> params(String path) {
        List<String> found = new ArrayList<>();
        Matcher m = PARAM.matcher(path);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String fill(String path, Map<String, Long> values) {
        Matcher m = PARAM.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            m.appendReplacement(out, values.containsKey(name) ? values.get(name).toString() : NOT_RESOURCE.get(name));
        }
        m.appendTail(out);
        return out.toString();
    }
}
