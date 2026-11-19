package com.famlog.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.support.IntegrationTest;

/** Flyway 마이그레이션(V1 계정, V2 Refresh 토큰)이 실제 PostgreSQL(PostGIS·pgvector 이미지)에 적용되고 사양서 10.2 제약이 걸리는지 확인한다. */
@Transactional
class FlywayMigrationTest extends IntegrationTest {

    @Test
    void 마이그레이션이_성공하고_V1이_적용된다() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).contains("1");
    }

    @Test
    void 계정_보안_테이블_5개가_있다() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("users", "social_accounts", "user_sessions", "device_tokens", "consents");
    }

    @Test
    void users에는_이메일_실명_전화번호_컬럼이_없다() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'users'", String.class);

        assertThat(columns).noneMatch(c -> c.contains("email") || c.contains("phone") || c.contains("real_name")
                || c.contains("password"));
    }

    @Test
    void postgis와_vector_확장이_설치되어_있다() {
        List<String> extensions = jdbcTemplate.queryForList("SELECT extname FROM pg_extension", String.class);

        assertThat(extensions).contains("postgis", "vector");
    }

    @Test
    void 같은_제공자_계정은_두_번_연결할_수_없다() {
        long userA = insertUser("엄마");
        long userB = insertUser("아빠");
        jdbcTemplate.update(
                "INSERT INTO social_accounts (user_id, provider, provider_user_id) VALUES (?, 'KAKAO', 'k-1')", userA);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO social_accounts (user_id, provider, provider_user_id) VALUES (?, 'KAKAO', 'k-1')", userB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정의되지_않은_사용자_상태는_저장되지_않는다() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (nickname, status) VALUES ('엄마', 'BANNED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정의되지_않은_소셜_제공자는_저장되지_않는다() {
        long userId = insertUser("딸");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO social_accounts (user_id, provider, provider_user_id) VALUES (?, 'NAVER', 'n-1')",
                userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 출생연도월은_YYYYMM_형식만_저장된다() {
        jdbcTemplate.update("INSERT INTO users (nickname, birth_ym) VALUES ('아들', '200803')");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO users (nickname, birth_ym) VALUES ('딸', '200813')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V2에서_Refresh_토큰_해시는_refresh_tokens로_옮겨졌다() {
        List<String> sessionColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'user_sessions'",
                String.class);

        assertThat(sessionColumns).doesNotContain("refresh_token_hash");
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success", String.class)).contains("1", "2");
    }

    @Test
    void refresh_토큰_해시는_중복될_수_없다() {
        long sessionId = insertSession(insertUser("엄마"));
        String insert = "INSERT INTO refresh_tokens (session_id, token_hash, status, expires_at) "
                + "VALUES (?, 'same-hash', ?, now() + interval '14 days')";
        jdbcTemplate.update(insert, sessionId, "ROTATED");

        assertThatThrownBy(() -> jdbcTemplate.update(insert, sessionId, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 세션당_ACTIVE_Refresh_토큰은_하나뿐이다() {
        long sessionId = insertSession(insertUser("엄마"));
        String insert = "INSERT INTO refresh_tokens (session_id, token_hash, status, expires_at) "
                + "VALUES (?, ?, ?, now() + interval '14 days')";
        jdbcTemplate.update(insert, sessionId, "h1", "ROTATED");
        jdbcTemplate.update(insert, sessionId, "h2", "ROTATED");
        jdbcTemplate.update(insert, sessionId, "h3", "ACTIVE");

        assertThatThrownBy(() -> jdbcTemplate.update(insert, sessionId, "h4", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 시각_컬럼은_timestamptz다() {
        List<String> types = jdbcTemplate.queryForList(
                "SELECT DISTINCT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name IN ('created_at', 'updated_at', 'expires_at', 'agreed_at')",
                String.class);

        assertThat(types).containsExactly("timestamp with time zone");
    }

    @Test
    void V3_그룹_테이블이_있고_그룹당_활성_OWNER는_한_명이다() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("family_groups", "group_members", "group_invites", "chat_rooms", "audit_logs");
        long owner = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, owner);
        String insert = "INSERT INTO group_members (group_id, user_id, role, calendar_color, joined_at) "
                + "VALUES (?, ?, 'OWNER', '#14A3B8', now())";
        jdbcTemplate.update(insert, groupId, owner);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, groupId, insertUser("아빠")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V4_방_안에서_seq는_겹칠_수_없고_시스템_메시지는_여러_개_둘_수_있다() {
        long sender = insertUser("엄마");
        long roomId = insertRoom(sender);
        jdbcTemplate.update(MESSAGE_INSERT, roomId, 1, sender, "00000000-0000-0000-0000-000000000001");
        // 시스템 메시지(보낸 사람·clientMessageId 없음)는 여러 개 둘 수 있다
        jdbcTemplate.update("INSERT INTO messages (room_id, seq, type, body) VALUES (?, 2, 'SYSTEM', '입장')", roomId);
        jdbcTemplate.update("INSERT INTO messages (room_id, seq, type, body) VALUES (?, 3, 'SYSTEM', '입장')", roomId);

        assertThatThrownBy(() -> jdbcTemplate.update(MESSAGE_INSERT, roomId, 1, sender,
                "00000000-0000-0000-0000-000000000002"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V4_같은_사람의_clientMessageId는_두_번_저장할_수_없다() {
        long sender = insertUser("엄마");
        long roomId = insertRoom(sender);
        jdbcTemplate.update(MESSAGE_INSERT, roomId, 1, sender, "00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> jdbcTemplate.update(MESSAGE_INSERT, roomId, 2, sender,
                "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V6_알림_설정의_방해_금지_시작과_끝은_같을_수_없다() {
        long userId = insertUser("엄마");
        jdbcTemplate.update("INSERT INTO user_notification_settings (user_id) VALUES (?)", userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_notification_settings SET quiet_start = '07:00' WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V7_일정은_끝이_시작보다_앞일_수_없고_리마인드_기본값은_전날_20시와_1시간_전이다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);
        String insert = "INSERT INTO schedules (group_id, title, starts_at, ends_at, created_by) "
                + "VALUES (?, '병원', ?::timestamptz, ?::timestamptz, ?) RETURNING id";

        long id = jdbcTemplate.queryForObject(insert, Long.class, groupId, "2026-11-21T10:00:00+09:00",
                "2026-11-21T10:00:00+09:00", userId);
        assertThat(jdbcTemplate.queryForObject("SELECT array_to_string(reminders, ',') FROM schedules WHERE id = ?",
                String.class, id)).isEqualTo("PREV_DAY_20,HOUR_1");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(insert, Long.class, groupId,
                "2026-11-21T10:00:00+09:00", "2026-11-21T09:59:00+09:00", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V7_할_일의_완료_시각과_완료한_사람은_함께_있거나_함께_없다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO todos (group_id, title, done_at, created_by) VALUES (?, '우유', now(), ?)", groupId,
                userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V7_정의되지_않은_할_일_종류는_저장되지_않는다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO todos (group_id, kind, title, created_by) VALUES (?, 'MEMO', '우유', ?)", groupId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V7_공휴일_초기_데이터가_있고_같은_날_같은_이름은_두_번_넣을_수_없다() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM holidays WHERE date BETWEEN '2026-01-01' AND '2027-12-31'", Long.class))
                .isEqualTo(40);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO holidays (date, name) VALUES ('2026-01-01', '신정')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V8_장소_좌표에서_geom이_만들어지고_같은_그룹에_같은_카카오_장소는_하나다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);
        String insert = "INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, "
                + "shared_by) VALUES (?, 'k-1', '냉면집', 'RESTAURANT', '서울 송파구', '서울', 37.5, 127.1, ?) RETURNING id";

        long id = jdbcTemplate.queryForObject(insert, Long.class, groupId, userId);
        assertThat(jdbcTemplate.queryForObject("SELECT ST_AsText(geom::geometry) FROM places WHERE id = ?",
                String.class, id)).isEqualTo("POINT(127.1 37.5)");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(insert, Long.class, groupId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V8_장소_별점은_1에서_5다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);
        String insert = "INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, "
                + "rating, shared_by) VALUES (?, ?, '집', ?, '서울', '서울', 37.5, 127.1, ?, ?)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, groupId, "k-2", "RESTAURANT", 6, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V8_정의되지_않은_장소_카테고리는_저장되지_않는다() {
        long userId = insertUser("엄마");
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, userId);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO places (group_id, kakao_place_id, name, category, "
                + "address, region1, lat, lng, shared_by) VALUES (?, 'k-3', '집', 'PARK', '서울', '서울', 37.5, 127.1, ?)",
                groupId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V8_위치_이용_기록에_장소_검색_목적이_더해졌고_좌표_컬럼은_없다() {
        long userId = insertUser("엄마");
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'location_access_logs'",
                String.class);

        assertThat(columns).containsExactlyInAnyOrder("id", "user_id", "purpose", "target_id", "accessed_at");
        assertThat(jdbcTemplate.update("INSERT INTO location_access_logs (user_id, purpose) VALUES (?, 'PLACE_SEARCH')",
                userId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO location_access_logs (user_id, purpose) VALUES (?, 'TRACKING')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V9_AI_요청_기록에는_대화_원문_컬럼이_없다() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_jobs'", String.class);

        assertThat(columns).contains("member_refs", "model_version", "prompt_version", "input_tokens",
                "output_tokens", "latency_ms", "status");
        assertThat(columns).noneMatch(c -> c.contains("body") || c.contains("text") || c.contains("prompt_body")
                || c.contains("response") || c.contains("payload"));
    }

    @Test
    void V9_정의되지_않은_AI_요청_상태는_저장되지_않는다() {
        long[] room = insertGroupRoom();

        assertThat(jdbcTemplate.update(AI_JOB_INSERT, room[0], room[1], 0, 3, "QUEUED")).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(AI_JOB_INSERT, room[0], room[1], 0, 3, "RUNNING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V9_빈_분석_구간은_저장되지_않는다() {
        long[] room = insertGroupRoom();

        assertThatThrownBy(() -> jdbcTemplate.update(AI_JOB_INSERT, room[0], room[1], 3, 3, "QUEUED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static final String AI_JOB_INSERT = "INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, "
            + "message_count, member_refs, status) VALUES (?, ?, ?, ?, 'RULE', 1, '{}', ?)";

    /** @return {groupId, roomId} */
    private long[] insertGroupRoom() {
        long roomId = insertRoom(insertUser("엄마"));
        long groupId = jdbcTemplate.queryForObject("SELECT group_id FROM chat_rooms WHERE id = ?", Long.class, roomId);
        return new long[] { groupId, roomId };
    }

    @Test
    void V9_그룹_AI_분석은_기본_꺼짐이고_분석_기준점은_0부터다() {
        long[] room = insertGroupRoom();
        long groupId = room[0];
        long roomId = room[1];

        jdbcTemplate.update("INSERT INTO group_settings (group_id) VALUES (?)", groupId);

        assertThat(jdbcTemplate.queryForObject("SELECT ai_analysis_enabled FROM group_settings WHERE group_id = ?",
                Boolean.class, groupId)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT ai_analyzed_seq FROM chat_rooms WHERE id = ?", Long.class,
                roomId)).isZero();
    }

    private static final String MESSAGE_INSERT = "INSERT INTO messages (room_id, seq, sender_id, client_message_id, "
            + "type, body) VALUES (?, ?, ?, ?::uuid, 'TEXT', '안녕')";

    private long insertRoom(long owner) {
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO family_groups (name, created_by) VALUES ('우리 가족', ?) RETURNING id", Long.class, owner);
        return jdbcTemplate.queryForObject("INSERT INTO chat_rooms (group_id) VALUES (?) RETURNING id", Long.class,
                groupId);
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject("INSERT INTO users (nickname) VALUES (?) RETURNING id", Long.class, nickname);
    }

    private long insertSession(long userId) {
        return jdbcTemplate.queryForObject("INSERT INTO user_sessions (user_id, device_id, platform, expires_at) "
                + "VALUES (?, 'device', 'ANDROID', now() + interval '14 days') RETURNING id", Long.class, userId);
    }
}
