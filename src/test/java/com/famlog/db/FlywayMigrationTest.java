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

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject("INSERT INTO users (nickname) VALUES (?) RETURNING id", Long.class, nickname);
    }

    private long insertSession(long userId) {
        return jdbcTemplate.queryForObject("INSERT INTO user_sessions (user_id, device_id, platform, expires_at) "
                + "VALUES (?, 'device', 'ANDROID', now() + interval '14 days') RETURNING id", Long.class, userId);
    }
}
