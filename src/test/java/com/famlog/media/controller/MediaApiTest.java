package com.famlog.media.controller;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.createGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.chat.domain.MessageType;
import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.media.service.MediaProperties;
import com.famlog.media.storage.MediaStorage;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.StompTestClient;
import com.jayway.jsonpath.JsonPath;

/**
 * 사진·동영상 업로드·전송·내려받기 (사양서 4.2 CHAT-05, 9.1, 9.9 SEC-T1·T6, 18.1 CHAT-05 서버 범위).
 * 실제 S3(SeaweedFS)에 Presigned URL로 올리고, 워커 대신 테스트가 결과 스트림에 처리 결과를 넣는다.
 * GPS 제거·썸네일 생성 자체는 워커 테스트(worker/tests)에서 확인한다.
 */
class MediaApiTest extends ChatIntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MediaStorage storage;

    @Autowired
    MediaProperties mediaProperties;

    long mom;
    long dad;
    long groupId;
    long stranger;
    long otherGroupId;

    @BeforeEach
    void setUpFamily() throws Exception {
        mom = createUser();
        dad = createUser();
        groupId = createGroup(mockMvc, mom, "우리 가족");
        addMember(mockMvc, groupId, mom, dad);
        stranger = createUser();
        otherGroupId = createGroup(mockMvc, stranger, "다른 가족");
    }

    // --- 업로드 ---

    @Test
    void URL을_받아_올리고_완료하면_UPLOADED가_되고_워커에_처리_요청이_간다() throws Exception {
        byte[] jpeg = jpeg();
        String body = uploadUrls(mom, groupId, item("image/jpeg", jpeg.length))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].method").value("PUT"))
                .andExpect(jsonPath("$.items[0].headers.Content-Type").value("image/jpeg"))
                .andExpect(jsonPath("$.items[0].expiresAt").value(matchesPattern(".*\\+09:00$")))
                .andReturn().getResponse().getContentAsString();
        long mediaId = ((Number) JsonPath.read(body, "$.items[0].mediaId")).longValue();
        String uploadUrl = JsonPath.read(body, "$.items[0].uploadUrl");

        assertThat(storageKey(mediaId)).matches("media/[0-9a-f-]{36}/original");
        assertThat(put(uploadUrl, "image/jpeg", jpeg)).isEqualTo(200);

        complete(mom, mediaId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.mime").value("image/jpeg"))
                .andExpect(jsonPath("$.size").value(jpeg.length));

        Map<Object, Object> job = lastJob();
        assertThat(job).containsEntry("mediaId", Long.toString(mediaId))
                .containsEntry("storageKey", storageKey(mediaId))
                .containsEntry("mediaType", "IMAGE")
                .containsEntry("extractLocation", "false");

        // 다시 불러도 같은 결과, 처리 요청은 다시 보내지 않는다
        long jobs = jobCount();
        complete(mom, mediaId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UPLOADED"));
        assertThat(jobCount()).isEqualTo(jobs);
    }

    @Test
    void 확장자만_바꾼_파일은_거부하고_지운다() throws Exception {
        byte[] png = png();
        long disguisedPng = uploadAs(mom, "image/jpeg", png);
        byte[] exe = "MZ\u0090\0\u0003\0\0\0\u0004\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        long disguisedExe = uploadAs(mom, "image/jpeg", exe);
        long imageAsVideo = uploadAs(mom, "video/mp4", jpeg());

        for (long mediaId : List.of(disguisedPng, disguisedExe, imageAsVideo)) {
            complete(mom, mediaId).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(statusOf(mediaId)).isEqualTo("REJECTED");
            assertThat(storage.size(storageKey(mediaId))).isEmpty();
            complete(mom, mediaId).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 올리지_않고_완료하면_400이고_다시_올릴_수_있다() throws Exception {
        byte[] jpeg = jpeg();
        String body = uploadUrls(mom, groupId, item("image/jpeg", jpeg.length)).andReturn().getResponse()
                .getContentAsString();
        long mediaId = ((Number) JsonPath.read(body, "$.items[0].mediaId")).longValue();

        complete(mom, mediaId).andExpect(status().isBadRequest());
        assertThat(statusOf(mediaId)).isEqualTo("PENDING_UPLOAD");

        put(JsonPath.read(body, "$.items[0].uploadUrl"), "image/jpeg", jpeg);
        complete(mom, mediaId).andExpect(status().isOk());
    }

    @Test
    void 개수_형식_크기를_넘으면_URL을_주지_않는다() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            tooMany.add(item("image/jpeg", 1000));
        }
        uploadUrls(mom, groupId, tooMany.toArray(String[]::new)).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId, item("application/pdf", 1000)).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId, item("image/jpeg", 30L * 1024 * 1024 + 1)).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId, item("video/mp4", 500L * 1024 * 1024 + 1)).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId, item("image/jpeg", 0)).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId).andExpect(status().isBadRequest());
        uploadUrls(mom, groupId, item("video/quicktime", 500L * 1024 * 1024)).andExpect(status().isCreated());
    }

    @Test
    void 다른_그룹에는_올릴_수_없고_남의_미디어는_404로_숨긴다() throws Exception {
        uploadUrls(stranger, groupId, item("image/jpeg", 1000))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));

        long mediaId = uploadAs(mom, "image/jpeg", jpeg());
        complete(stranger, mediaId).andExpect(status().isNotFound());
        complete(dad, mediaId).andExpect(status().isNotFound());
        complete(mom, 999_999_999L).andExpect(status().isNotFound());
    }

    // --- 메시지로 보내기 ---

    @Test
    void 사진_여러_장을_한_묶음으로_보내면_보낸_순서대로_붙는다() throws Exception {
        long first = uploadedImage(mom);
        long second = uploadedImage(mom);
        long third = uploadedImage(mom);

        MessageResponse message = sendMedia(groupId, mom, MessageType.IMAGE, List.of(third, first, second));

        assertThat(message.type()).isEqualTo(MessageType.IMAGE);
        assertThat(message.body()).isNull();
        assertThat(message.media()).extracting(media -> media.id()).containsExactly(third, first, second);
        assertThat(message.media()).allSatisfy(media -> assertThat(media.status().name()).isEqualTo("UPLOADED"));

        mockMvc.perform(get("/api/v1/groups/{g}/messages", groupId).with(asUser(dad)))
                .andExpect(jsonPath("$.items[-1:].media[*].id", contains((int) third, (int) first, (int) second)));
        mockMvc.perform(get("/api/v1/groups/me").with(asUser(dad)))
                .andExpect(jsonPath("$[0].lastMessage.type").value("IMAGE"));
    }

    @Test
    void 보낼_수_없는_미디어_조합은_거부한다() throws Exception {
        long image = uploadedImage(mom);
        long video = uploadedVideo(mom);
        long dadImage = uploadedImage(dad);
        long pending = pendingImage(mom);
        long otherGroupImage = uploadedImageIn(stranger, otherGroupId);
        long cleanedUp = Long.MAX_VALUE; // 정리 배치가 지운 미디어

        // 붙일 수 없는 미디어: 앱은 MEDIA_UNAVAILABLE 을 받으면 다시 올린다
        for (List<Long> ids : List.of(List.of(video), List.of(dadImage), List.of(pending), List.of(otherGroupImage),
                List.of(cleanedUp))) {
            assertThatThrownBy(() -> sendMedia(groupId, mom, MessageType.IMAGE, ids)).as(ids.toString())
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.MEDIA_UNAVAILABLE);
        }
        assertThatThrownBy(() -> sendMedia(groupId, mom, MessageType.VIDEO, List.of(image)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.MEDIA_UNAVAILABLE);
        // 형식 오류는 VALIDATION_FAILED
        for (List<Long> ids : List.of(List.of(image, image), List.<Long>of())) {
            assertThatThrownBy(() -> sendMedia(groupId, mom, MessageType.IMAGE, ids)).as(ids.toString())
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> sendMedia(groupId, mom, MessageType.VIDEO, List.of(video, uploadedVideo(mom))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        sendMedia(groupId, mom, MessageType.IMAGE, List.of(image));
        assertThatThrownBy(() -> sendMedia(groupId, mom, MessageType.IMAGE, List.of(image))).as("이미 보낸 사진")
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.MEDIA_UNAVAILABLE);
        assertThat(sendMedia(groupId, mom, MessageType.VIDEO, List.of(video)).media()).hasSize(1);
    }

    @Test
    void 처리_결과가_오면_READY가_되고_그룹에_media_updated가_간다() throws Exception {
        long mediaId = uploadedImage(mom);
        try (StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> topic = dadClient.subscribeGroup(groupId);
            long messageId = sendMedia(groupId, mom, MessageType.IMAGE, List.of(mediaId)).id();

            publishReady(mediaId);

            String event = nextOfType(topic, "media.updated");
            assertThat((Object) JsonPath.read(event, "$.data.messageId")).isEqualTo((int) messageId);
            assertThat((Object) JsonPath.read(event, "$.data.media.status")).isEqualTo("READY");
            assertThat((Object) JsonPath.read(event, "$.data.media.width")).isEqualTo(1440);
        }
        assertThat(statusOf(mediaId)).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT location IS NULL FROM media_assets WHERE id = ?",
                Boolean.class, mediaId)).isTrue();
    }

    // --- 내려받기 ---

    @Test
    void 처리가_끝나면_10분짜리_주소로_넘겨주고_그_주소로_받을_수_있다() throws Exception {
        byte[] jpeg = jpeg();
        long mediaId = uploadAs(mom, "image/jpeg", jpeg);
        complete(mom, mediaId).andExpect(status().isOk());
        sendMedia(groupId, mom, MessageType.IMAGE, List.of(mediaId));

        content(dad, mediaId, "ORIGINAL").andExpect(status().isNotFound());
        publishReady(mediaId);
        awaitStatus(mediaId, "READY");

        String location = content(dad, mediaId, "ORIGINAL")
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).contains(storageKey(mediaId)).contains("X-Amz-Expires=600");
        HttpResponse<byte[]> downloaded = HTTP.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).isEqualTo(jpeg);

        assertThat(content(dad, mediaId, "THUMB_M").andExpect(status().isFound()).andReturn().getResponse()
                .getHeader("Location")).contains("media/thumb-m-" + mediaId);
    }

    @Test
    void 볼_수_없는_미디어는_404로_숨긴다() throws Exception {
        long sent = uploadedImage(mom);
        long messageId = sendMedia(groupId, mom, MessageType.IMAGE, List.of(sent)).id();
        long notSent = uploadedImage(mom);
        publishReady(sent);
        publishReady(notSent);
        awaitStatus(sent, "READY");
        awaitStatus(notSent, "READY");

        content(stranger, sent, "THUMB_S").andExpect(status().isNotFound());
        content(dad, notSent, "THUMB_S").andExpect(status().isNotFound());
        content(mom, notSent, "THUMB_S").andExpect(status().isFound());

        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);
        content(daughter, sent, "THUMB_S").andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/messages/{id}", messageId).with(asUser(mom)))
                .andExpect(status().isNoContent());
        content(dad, sent, "THUMB_S").andExpect(status().isNotFound());
        content(dad, sent, "NOPE").andExpect(status().isBadRequest());
    }

    // --- 도우미 ---

    private ResultActions uploadUrls(long userId, long group, String... items) throws Exception {
        return mockMvc.perform(post("/api/v1/media/upload-urls").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":%d,\"items\":[%s]}".formatted(group, String.join(",", items))));
    }

    private static String item(String mimeType, long size) {
        return "{\"mimeType\":\"%s\",\"size\":%d}".formatted(mimeType, size);
    }

    private ResultActions complete(long userId, long mediaId) throws Exception {
        return mockMvc.perform(post("/api/v1/media/{id}/complete", mediaId).with(asUser(userId)));
    }

    private ResultActions content(long userId, long mediaId, String variant) throws Exception {
        return mockMvc.perform(get("/api/v1/media/{id}/content", mediaId).param("variant", variant)
                .with(asUser(userId)));
    }

    /** URL을 받아 파일을 올린다 (완료는 부르지 않는다) */
    private long uploadAs(long userId, String declaredMime, byte[] data) throws Exception {
        return uploadIn(userId, groupId, declaredMime, data);
    }

    private long uploadIn(long userId, long group, String declaredMime, byte[] data) throws Exception {
        String body = uploadUrls(userId, group, item(declaredMime, data.length)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(put(JsonPath.read(body, "$.items[0].uploadUrl"), declaredMime, data)).isEqualTo(200);
        return ((Number) JsonPath.read(body, "$.items[0].mediaId")).longValue();
    }

    private long uploadedImage(long userId) throws Exception {
        return uploadedImageIn(userId, groupId);
    }

    private long uploadedImageIn(long userId, long group) throws Exception {
        long mediaId = uploadIn(userId, group, "image/jpeg", jpeg());
        complete(userId, mediaId).andExpect(status().isOk());
        return mediaId;
    }

    private long uploadedVideo(long userId) throws Exception {
        byte[] mp4 = "\0\0\0\u0018ftypmp42\0\0\0\0mp42isom\0\0\0\u0008mdat"
                .getBytes(StandardCharsets.ISO_8859_1);
        long mediaId = uploadAs(userId, "video/mp4", mp4);
        complete(userId, mediaId).andExpect(status().isOk());
        return mediaId;
    }

    private long pendingImage(long userId) throws Exception {
        String body = uploadUrls(userId, groupId, item("image/jpeg", 1000)).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.items[0].mediaId")).longValue();
    }

    private static int put(String url, String contentType, byte[] data) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** 워커가 보내는 것과 같은 처리 결과 (좌표 없음) */
    private void publishReady(long mediaId) {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(mediaProperties.streams().results())
                .ofMap(Map.of("mediaId", Long.toString(mediaId), "status", "READY", "mime", "image/jpeg",
                        "size", "1234", "width", "1440", "height", "1080",
                        "takenAt", "2026-11-14T11:30:00+09:00",
                        "variants", "{\"THUMB_S\":\"media/thumb-s-%d\",\"THUMB_M\":\"media/thumb-m-%d\",\"THUMB_L\":\"media/thumb-l-%d\"}"
                                .formatted(mediaId, mediaId, mediaId)));
        redis.opsForStream().add(record);
    }

    private void awaitStatus(long mediaId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(StompTestClient.WAIT_SECONDS);
        while (!expected.equals(statusOf(mediaId)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(statusOf(mediaId)).isEqualTo(expected);
    }

    private static String nextOfType(BlockingQueue<String> queue, String type) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(StompTestClient.WAIT_SECONDS);
        while (Instant.now().isBefore(deadline)) {
            String event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && type.equals(JsonPath.read(event, "$.type"))) {
                return event;
            }
        }
        throw new AssertionError(type + " 이벤트를 받지 못했습니다");
    }

    private String statusOf(long mediaId) {
        return jdbcTemplate.queryForObject("SELECT status FROM media_assets WHERE id = ?", String.class, mediaId);
    }

    private String storageKey(long mediaId) {
        return jdbcTemplate.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?", String.class,
                mediaId);
    }

    private Map<Object, Object> lastJob() {
        var records = redis.opsForStream().reverseRange(mediaProperties.streams().jobs(),
                org.springframework.data.domain.Range.unbounded(),
                org.springframework.data.redis.connection.Limit.limit().count(1));
        assertThat(records).isNotEmpty();
        return records.get(0).getValue();
    }

    private long jobCount() {
        Long size = redis.opsForStream().size(mediaProperties.streams().jobs());
        return size == null ? 0 : size;
    }

    private static byte[] jpeg() throws Exception {
        return encode("jpg");
    }

    private static byte[] png() throws Exception {
        return encode("png");
    }

    private static byte[] encode(String format) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, (int) (Math.random() * 0xFFFFFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
