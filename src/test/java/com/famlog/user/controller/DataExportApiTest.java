package com.famlog.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.famlog.media.service.MediaProperties;
import com.famlog.media.storage.MediaStorage;
import com.famlog.support.GroupApi;
import com.famlog.support.PlaceApi;
import com.famlog.support.PushIntegrationTest;
import com.famlog.support.ScheduleApi;
import com.famlog.user.service.AccountDeletionService;
import com.famlog.user.service.DataExportService;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 내 데이터 내보내기 (사양서 4.1 ACC-02, 18장 "ZIP 준비 후 알림이 오고, 링크는 7일 뒤 만료된다"): 요청 → 배치가 ZIP 생성 →
 * 푸시 → 다운로드 링크, 내 데이터만 들어감, 24시간 제한, 실패 재시도, 7일 만료, 다른 사람 요청은 404, 계정 파기 때 삭제
 */
class DataExportApiTest extends PushIntegrationTest {

    @Autowired
    DataExportService exportService;

    @Autowired
    AccountDeletionService accountDeletionService;

    @Autowired
    S3Client s3;

    @Autowired
    MediaStorage storage;

    @Autowired
    MediaProperties mediaProperties;

    @Test
    void 요청하면_ZIP이_준비되고_알림이_오며_내_데이터만_들어_있다() throws Exception {
        long me = createUser();
        long dad = createUser();
        String device = deviceOf(me);
        long group = GroupApi.createGroup(mockMvc, me, "우리 가족");
        GroupApi.addMember(mockMvc, group, me, dad);
        send(group, me, "내가 보낸 메시지");
        send(group, dad, "아빠가 보낸 메시지");
        long schedule = ScheduleApi.create(mockMvc, group, me, ScheduleApi.timed("병원", "2026-12-01T10:00",
                "2026-12-01T11:00", me));
        ScheduleApi.create(mockMvc, group, dad, ScheduleApi.timed("아빠 약속", "2026-12-02T10:00",
                "2026-12-02T11:00", dad));
        jdbcTemplate.update("INSERT INTO todos (group_id, title, assignee_id, created_by) VALUES (?, '우유 사기', ?, ?)",
                group, me, dad);
        long place = jdbcTemplate.queryForObject("""
                INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, shared_by)
                VALUES (?, ?, '단골 식당', 'RESTAURANT', '서울 어딘가', '서울', 37.5, 127.0, ?) RETURNING id""",
                Long.class, group, PlaceApi.newKakaoId(), me);
        jdbcTemplate.update("INSERT INTO place_marks (place_id, user_id, kind) VALUES (?, ?, 'WANT')", place, me);
        String photoKey = "test/" + UUID.randomUUID();
        s3.putObject(builder -> builder.bucket(mediaProperties.s3().bucket()).key(photoKey),
                RequestBody.fromString("x"));
        jdbcTemplate.update("""
                INSERT INTO media_assets (group_id, uploader_id, media_type, mime, size, storage_key, status,
                    upload_expires_at) VALUES (?, ?, 'IMAGE', 'image/jpeg', 1, ?, 'READY', now())""",
                group, me, photoKey);

        String body = mockMvc.perform(post("/api/v1/users/me/data-exports").with(asUser(me)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long exportId = ((Number) JsonPath.read(body, "$.id")).longValue();
        // 진행 중이면 같은 요청
        mockMvc.perform(post("/api/v1/users/me/data-exports").with(asUser(me)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(exportId));

        runBatch();

        pushSender.await(device, data -> "DATA_EXPORT_READY".equals(data.get("type"))
                && Long.toString(exportId).equals(data.get("exportId")) && "SYSTEM".equals(data.get("channel")));
        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId).with(asUser(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.downloadUrl").value(containsString(
                        "response-content-disposition")))
                .andExpect(jsonPath("$.fileName").value(matchesPattern(
                        "famlog-export-\\d{8}\\.zip")))
                .andExpect(jsonPath("$.expiresAt").value(endsWith("+09:00")));
        mockMvc.perform(get("/api/v1/users/me/data-exports").with(asUser(me)))
                .andExpect(jsonPath("$.items[0].id").value(exportId))
                .andExpect(jsonPath("$.items[0].downloadUrl").doesNotExist());

        Map<String, String> zip = unzip(storageKey(exportId));
        assertThat(zip).containsOnlyKeys("README.txt", "profile.json", "groups.json", "messages.json", "media.json",
                "schedules.json", "todos.json", "places.json", "consents.json");
        assertThat(zip.get("messages.json")).contains("내가 보낸 메시지").doesNotContain("아빠가 보낸 메시지");
        assertThat(zip.get("schedules.json")).contains("병원").doesNotContain("아빠 약속");
        assertThat(JsonPath.<Integer>read(zip.get("schedules.json"), "$[0].id")).isEqualTo((int) schedule);
        assertThat(zip.get("todos.json")).contains("우유 사기", "\"assignedToMe\" : true");
        assertThat(zip.get("places.json")).contains("단골 식당", "WANT");
        assertThat(zip.get("media.json")).contains(photoKey, "X-Amz-Expires=604800");
        assertThat(zip.get("groups.json")).contains("우리 가족", "OWNER");
        assertThat(zip.get("messages.json")).contains("+09:00");

        assertThat(jdbcTemplate.queryForObject("SELECT meta::text FROM audit_logs WHERE action = 'DATA_EXPORT_READY' "
                + "AND target_id = ?", String.class, exportId)).contains("\"messages\": 1").doesNotContain("메시지");
        assertThat(auditCount("DATA_EXPORT_REQUESTED", exportId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId).with(asUser(me)))
                .andExpect(status().isOk());
        assertThat(auditCount("DATA_EXPORT_DOWNLOADED", exportId)).isEqualTo(1);
    }

    @Test
    void 준비된_지_24시간_안에는_다시_요청할_수_없고_실패한_요청은_세지_않는다() throws Exception {
        long me = createUser();
        long first = requestExport(me);
        jdbcTemplate.update("UPDATE data_export_requests SET status = 'FAILED' WHERE id = ?", first);

        long second = requestExport(me);
        runBatch();
        assertThat(statusOf(second)).isEqualTo("READY");

        mockMvc.perform(post("/api/v1/users/me/data-exports").with(asUser(me)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        jdbcTemplate.update("UPDATE data_export_requests SET requested_at = now() - interval '25 hours' WHERE id = ?",
                second);
        assertThat(requestExport(me)).isNotEqualTo(second);
    }

    @Test
    void 다른_사람의_요청은_404이고_로그인하지_않으면_401이다() throws Exception {
        long me = createUser();
        long other = createUser();
        long exportId = requestExport(me);

        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId).with(asUser(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/me/data-exports").with(asUser(other)))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(post("/api/v1/users/me/data-exports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId)).andExpect(status().isUnauthorized());
    }

    @Test
    void 일주일이_지나면_파일을_지우고_링크를_주지_않는다() throws Exception {
        long me = createUser();
        long exportId = requestExport(me);
        runBatch();
        String key = storageKey(exportId);
        assertThat(storage.size(key)).isPresent();

        jdbcTemplate.update("UPDATE data_export_requests SET expires_at = now() - interval '1 minute' WHERE id = ?",
                exportId);
        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId).with(asUser(me)))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
        assertThat(exportService.expireDue()).isPositive();

        assertThat(storage.size(key)).isEmpty();
        mockMvc.perform(get("/api/v1/users/me/data-exports/{id}", exportId).with(asUser(me)))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    @Test
    void 멈춘_작업은_다시_잡고_시도_횟수를_다_쓰면_실패로_둔다() throws Exception {
        long me = createUser();
        long exportId = requestExport(me);
        // 서버가 만들다 멈춘 것처럼
        jdbcTemplate.update("UPDATE data_export_requests SET status = 'PROCESSING', attempts = 1, "
                + "started_at = now() - interval '1 hour' WHERE id = ?", exportId);
        runBatch();
        assertThat(statusOf(exportId)).isEqualTo("READY");

        long other = createUser();
        long stuck = requestExport(other);
        jdbcTemplate.update("UPDATE data_export_requests SET status = 'PROCESSING', attempts = 3, "
                + "started_at = now() - interval '1 hour' WHERE id = ?", stuck);
        runBatch();
        assertThat(statusOf(stuck)).isEqualTo("FAILED");
        // 실패했으니 바로 다시 요청할 수 있다
        assertThat(requestExport(other)).isNotEqualTo(stuck);
    }

    @Test
    void 삭제_예정_계정의_요청은_만들지_않고_계정을_파기하면_파일도_지운다() throws Exception {
        long me = createUser();
        long ready = requestExport(me);
        runBatch();
        String key = storageKey(ready);
        jdbcTemplate.update("UPDATE data_export_requests SET requested_at = now() - interval '25 hours' WHERE id = ?",
                ready);
        long pending = requestExport(me);
        mockMvc.perform(delete("/api/v1/users/me")
                .with(asUser(me))).andExpect(status().isNoContent());

        runBatch();
        assertThat(statusOf(pending)).isEqualTo("FAILED");

        jdbcTemplate.update("UPDATE account_deletion_requests SET purge_after = now() - interval '1 minute' "
                + "WHERE user_id = ?", me);
        long requestId = jdbcTemplate.queryForObject("SELECT id FROM account_deletion_requests WHERE user_id = ?",
                Long.class, me);
        accountDeletionService.purge(requestId);

        assertThat(storage.size(key)).isEmpty();
        assertThat(statusOf(ready)).isEqualTo("EXPIRED");
    }

    private long requestExport(long userId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/users/me/data-exports").with(asUser(userId)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** 배치 한 번 (테스트는 스케줄러를 끈다) */
    private void runBatch() {
        for (Optional<Long> next = exportService.claimNext(); next.isPresent(); next = exportService.claimNext()) {
            exportService.process(next.get());
        }
    }

    private String statusOf(long exportId) {
        return jdbcTemplate.queryForObject("SELECT status FROM data_export_requests WHERE id = ?", String.class,
                exportId);
    }

    private String storageKey(long exportId) {
        return jdbcTemplate.queryForObject("SELECT storage_key FROM data_export_requests WHERE id = ?", String.class,
                exportId);
    }

    private int auditCount(String action, long exportId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                Integer.class, action, exportId);
    }

    private Map<String, String> unzip(String key) throws IOException {
        byte[] bytes = s3.getObjectAsBytes(builder -> builder.bucket(mediaProperties.s3().bucket()).key(key))
                .asByteArray();
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
