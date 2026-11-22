package com.famlog.user.service;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.time.FamlogTime;
import com.famlog.media.storage.MediaStorage;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내보내기 ZIP 을 임시 파일에 쓴다 (사양서 4.1 ACC-02). 시각은 +09:00, 사진·동영상은 만료 시각까지 유효한 원본 링크.
 * <ul>
 * <li>profile·groups·consents: 내 프로필·로그인 방법·알림 설정, 속했던 모든 그룹의 역할·호칭, 동의 내역</li>
 * <li>messages·media: 속했던 모든 그룹에서 내가 쓴 메시지(지운 것·시스템 메시지 제외)와 내가 올린 사진·동영상</li>
 * <li>schedules·todos·places: 지금 속한 그룹에서 내가 만들었거나 참여·담당한 일정·할 일, 내가 공유한 장소와 내 장소 표시</li>
 * </ul>
 * 다른 가족의 닉네임 등은 넣지 않는다. 메시지·사진은 페이지 단위로 읽어 한꺼번에 메모리에 올리지 않는다
 */
@Component
class DataExportWriter {

    private static final int FETCH_SIZE = 500;
    private static final DateTimeFormatter README_TIME = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");
    private static final String MEMBER_OF = """
            EXISTS (SELECT 1 FROM group_members gm WHERE gm.group_id = %s.group_id AND gm.user_id = :userId
                    AND gm.left_at IS NULL)""";

    private final NamedParameterJdbcTemplate jdbc;
    private final MediaStorage storage;
    private final ObjectWriter writer;

    DataExportWriter(DataSource dataSource, MediaStorage storage, JsonMapper jsonMapper) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.storage = storage;
        this.writer = jsonMapper.writerWithDefaultPrettyPrinter();
    }

    record Profile(long id, String nickname, String birthYearMonth, String uiMode, Instant joinedAt,
            Instant ageVerifiedAt, URI profileImageUrl, List<Login> logins, Notification notificationSettings) {
    }

    record Login(String provider, Instant connectedAt) {
    }

    record Notification(boolean previewHidden, boolean quietEnabled, LocalTime quietStart, LocalTime quietEnd) {
    }

    record Membership(long groupId, String groupName, String role, String familyRole, String familyTitle,
            String calendarColor, boolean aiOptOut, boolean notificationMuted, Instant joinedAt, Instant leftAt) {
    }

    record MessageRow(long id, long groupId, String groupName, long seq, String type, String body, Long replyToId,
            Instant sentAt) {
    }

    record MediaRow(long id, long groupId, String groupName, Long messageId, String mediaType, String mime,
            long size, Integer width, Integer height, Integer durationSec, Instant takenAt, Location location,
            Instant uploadedAt, URI url) {
    }

    record Location(double lat, double lng) {
    }

    record ScheduleRow(long id, long groupId, String groupName, String title, Instant startsAt, Instant endsAt,
            boolean allDay, String rrule, String calendarType, String lunarMonthDay, String memo, String placeName,
            boolean createdByMe, boolean participant, Instant createdAt) {
    }

    record TodoRow(long id, long groupId, String groupName, String kind, String title, LocalDate dueDate,
            Instant doneAt, boolean createdByMe, boolean assignedToMe, Instant createdAt) {
    }

    record Places(List<SharedPlace> shared, List<PlaceMark> marks) {
    }

    record SharedPlace(long id, long groupId, String groupName, String name, String category, String address,
            String phone, String placeUrl, Integer rating, String oneLineReview, Instant sharedAt) {
    }

    record PlaceMark(long placeId, long groupId, String groupName, String placeName, String kind, Instant markedAt) {
    }

    record ConsentRow(String type, String docVersion, Instant agreedAt, Instant withdrawnAt) {
    }

    /**
     * @param expiresAt 원본 링크가 만료되는 시각 (ZIP 과 같다)
     * @return 항목별 건수
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> write(long userId, Path zip, Instant now, Instant expiresAt) {
        Map<String, Object> params = Map.of("userId", userId);
        Duration linkTtl = Duration.between(now, expiresAt);
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "README.txt", readme(expiresAt).getBytes(StandardCharsets.UTF_8));
            putJson(out, "profile.json", profile(params, linkTtl));

            List<Membership> groups = jdbc.query("""
                    SELECT gm.group_id, g.name, gm.role, gm.family_role, gm.family_title, gm.calendar_color,
                        gm.ai_opt_out, gm.notif_muted, gm.joined_at, gm.left_at
                    FROM group_members gm JOIN family_groups g ON g.id = gm.group_id
                    WHERE gm.user_id = :userId ORDER BY gm.joined_at, gm.id
                    """, params, (rs, i) -> new Membership(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8),
                    instant(rs, 9), instant(rs, 10)));
            putJson(out, "groups.json", groups);
            counts.put("groups", groups.size());

            counts.put("messages", streamJson(out, "messages.json", """
                    SELECT m.id, r.group_id, g.name, m.seq, m.type, m.body, m.reply_to_id, m.created_at
                    FROM messages m JOIN chat_rooms r ON r.id = m.room_id JOIN family_groups g ON g.id = r.group_id
                    WHERE m.sender_id = :userId AND m.deleted_at IS NULL AND m.type NOT IN ('SYSTEM', 'CANDIDATE')
                    ORDER BY m.id
                    """, params, rs -> new MessageRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                    rs.getString(5), rs.getString(6), rs.getObject(7, Long.class), instant(rs, 8))));

            counts.put("media", streamJson(out, "media.json", """
                    SELECT a.id, a.group_id, g.name, a.message_id, a.media_type, a.mime, a.size, a.width, a.height,
                        a.duration_sec, a.taken_at, ST_Y(a.location::geometry), ST_X(a.location::geometry),
                        a.created_at, a.storage_key
                    FROM media_assets a JOIN family_groups g ON g.id = a.group_id
                        LEFT JOIN messages m ON m.id = a.message_id
                    WHERE a.uploader_id = :userId AND a.status IN ('UPLOADED', 'READY') AND m.deleted_at IS NULL
                    ORDER BY a.id
                    """, params, rs -> new MediaRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getObject(4, Long.class), rs.getString(5), rs.getString(6), rs.getLong(7),
                    rs.getObject(8, Integer.class), rs.getObject(9, Integer.class), rs.getObject(10, Integer.class),
                    instant(rs, 11), rs.getObject(12) == null ? null : new Location(rs.getDouble(12), rs.getDouble(13)),
                    instant(rs, 14), storage.presignGet(rs.getString(15), linkTtl))));

            List<ScheduleRow> schedules = jdbc.query("""
                    SELECT s.id, s.group_id, g.name, s.title, s.starts_at, s.ends_at, s.all_day, s.rrule,
                        s.calendar_type, s.lunar_month_day, s.memo, p.name, s.created_by = :userId,
                        EXISTS (SELECT 1 FROM schedule_participants sp WHERE sp.schedule_id = s.id
                                AND sp.user_id = :userId) AS participant, s.created_at
                    FROM schedules s JOIN family_groups g ON g.id = s.group_id LEFT JOIN places p ON p.id = s.place_id
                    WHERE %s AND (s.created_by = :userId OR EXISTS (SELECT 1 FROM schedule_participants sp
                        WHERE sp.schedule_id = s.id AND sp.user_id = :userId))
                    ORDER BY s.starts_at, s.id
                    """.formatted(MEMBER_OF.formatted("s")), params, (rs, i) -> new ScheduleRow(rs.getLong(1),
                    rs.getLong(2), rs.getString(3), rs.getString(4), instant(rs, 5), instant(rs, 6), rs.getBoolean(7),
                    rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                    rs.getBoolean(13), rs.getBoolean(14), instant(rs, 15)));
            putJson(out, "schedules.json", schedules);
            counts.put("schedules", schedules.size());

            List<TodoRow> todos = jdbc.query("""
                    SELECT t.id, t.group_id, g.name, t.kind, t.title, t.due_date, t.done_at,
                        t.created_by = :userId, t.assignee_id IS NOT DISTINCT FROM :userId, t.created_at
                    FROM todos t JOIN family_groups g ON g.id = t.group_id
                    WHERE %s AND (t.created_by = :userId OR t.assignee_id = :userId OR t.done_by = :userId)
                    ORDER BY t.id
                    """.formatted(MEMBER_OF.formatted("t")), params, (rs, i) -> new TodoRow(rs.getLong(1),
                    rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, LocalDate.class), instant(rs, 7), rs.getBoolean(8), rs.getBoolean(9),
                    instant(rs, 10)));
            putJson(out, "todos.json", todos);
            counts.put("todos", todos.size());

            List<SharedPlace> shared = jdbc.query("""
                    SELECT p.id, p.group_id, g.name, p.name, p.category, p.address, p.phone, p.place_url, p.rating,
                        p.one_line_review, p.created_at
                    FROM places p JOIN family_groups g ON g.id = p.group_id
                    WHERE p.shared_by = :userId AND %s ORDER BY p.id
                    """.formatted(MEMBER_OF.formatted("p")), params, (rs, i) -> new SharedPlace(rs.getLong(1),
                    rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), rs.getObject(9, Integer.class), rs.getString(10),
                    instant(rs, 11)));
            List<PlaceMark> marks = jdbc.query("""
                    SELECT pm.place_id, p.group_id, g.name, p.name, pm.kind, pm.created_at
                    FROM place_marks pm JOIN places p ON p.id = pm.place_id JOIN family_groups g ON g.id = p.group_id
                    WHERE pm.user_id = :userId AND %s ORDER BY pm.created_at, pm.place_id
                    """.formatted(MEMBER_OF.formatted("p")), params, (rs, i) -> new PlaceMark(rs.getLong(1),
                    rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), instant(rs, 6)));
            putJson(out, "places.json", new Places(shared, marks));
            counts.put("places", shared.size());
            counts.put("placeMarks", marks.size());

            List<ConsentRow> consents = jdbc.query("""
                    SELECT type, doc_version, agreed_at, withdrawn_at FROM consents WHERE user_id = :userId ORDER BY id
                    """, params, (rs, i) -> new ConsentRow(rs.getString(1), rs.getString(2), instant(rs, 3),
                    instant(rs, 4)));
            putJson(out, "consents.json", consents);
            counts.put("consents", consents.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return counts;
    }

    private Profile profile(Map<String, Object> params, Duration linkTtl) {
        List<Login> logins = jdbc.query("SELECT provider, created_at FROM social_accounts WHERE user_id = :userId "
                + "ORDER BY id", params, (rs, i) -> new Login(rs.getString(1), instant(rs, 2)));
        Notification notification = jdbc.query("""
                SELECT preview_hidden, quiet_enabled, quiet_start, quiet_end FROM user_notification_settings
                WHERE user_id = :userId
                """, params, (rs, i) -> new Notification(rs.getBoolean(1), rs.getBoolean(2),
                rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class))).stream().findFirst()
                .orElse(null);
        return jdbc.queryForObject("""
                SELECT id, nickname, birth_ym, ui_mode, created_at, age_verified_at, profile_image_key
                FROM users WHERE id = :userId
                """, params, (rs, i) -> new Profile(rs.getLong(1), rs.getString(2), birthYearMonth(rs.getString(3)),
                rs.getString(4), instant(rs, 5), instant(rs, 6),
                rs.getString(7) == null ? null : storage.presignGet(rs.getString(7), linkTtl), logins, notification));
    }

    /** 행을 하나씩 읽어 JSON 배열로 바로 쓴다. 쓴 행 수를 돌려준다 */
    private <T> int streamJson(ZipOutputStream out, String name, String sql, Map<String, Object> params,
            RowReader<T> reader) throws IOException {
        out.putNextEntry(entry(name));
        int[] count = { 0 };
        try (JsonGenerator generator = writer.createGenerator(new NonClosing(out))) {
            generator.writeStartArray();
            jdbc.query(sql, params, (RowCallbackHandler) rs -> {
                generator.writePOJO(reader.read(rs));
                count[0]++;
            });
            generator.writeEndArray();
        }
        out.closeEntry();
        return count[0];
    }

    private void putJson(ZipOutputStream out, String name, Object value) throws IOException {
        out.putNextEntry(entry(name));
        writer.writeValue(new NonClosing(out), value);
        out.closeEntry();
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(entry(name));
        out.write(bytes);
        out.closeEntry();
    }

    /** 압축 프로그램이 한국 시각으로 보이도록 (ZIP 시각에는 시간대가 없다) */
    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.now(FamlogTime.DISPLAY_ZONE));
        return entry;
    }

    private static String readme(Instant expiresAt) {
        return """
                팸로그(FamLog) 내 데이터 내보내기

                profile.json    내 프로필, 로그인 방법, 알림 설정
                groups.json     속했던 가족 그룹과 역할·호칭 (나간 그룹 포함)
                messages.json   내가 보낸 메시지 (지운 메시지·시스템 메시지 제외)
                media.json      내가 올린 사진·동영상 목록과 원본 링크
                schedules.json  지금 속한 그룹에서 내가 만들었거나 참여하는 일정
                todos.json      지금 속한 그룹에서 내가 만들었거나 맡은 할 일
                places.json     지금 속한 그룹에 내가 공유한 장소와 내 장소 표시(가 봤어요·가고 싶어요)
                consents.json   동의 내역

                시각은 한국 시간(+09:00)이에요.
                원본 링크는 %s까지 열 수 있어요. 필요한 사진·동영상은 그 전에 내려받아 주세요.
                """.formatted(README_TIME.format(expiresAt.atZone(FamlogTime.DISPLAY_ZONE)));
    }

    /** "199001" → "1990-01" */
    private static String birthYearMonth(String value) {
        return value == null || value.length() != 6 ? value : value.substring(0, 4) + "-" + value.substring(4);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** JSON 을 다 써도 ZIP 스트림은 닫지 않는다 (다음 항목을 이어 쓴다) */
    private static final class NonClosing extends FilterOutputStream {

        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
