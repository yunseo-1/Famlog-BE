package com.famlog.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.famlog.auth.domain.Platform;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.support.IntegrationTest;

/** Refresh 토큰 교체·재사용 감지·세션 폐기 (사양서 9.2, 9.9 SEC-T3). */
class SessionTokenServiceTest extends IntegrationTest {

    @Autowired
    SessionTokenService service;

    @Autowired
    SessionRevocationStore revocationStore;

    @Test
    void 로그인하면_세션과_토큰이_생기고_DB에는_Refresh_원문이_아니라_해시만_남는다() {
        long userId = createUser();

        TokenPair tokens = service.startSession(userId, "device-1", "Pixel 9", Platform.ANDROID);

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).hasSizeGreaterThanOrEqualTo(43);
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE session_id = ?", String.class, tokens.sessionId());
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(64).isNotEqualTo(tokens.refreshToken());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, tokens.refreshToken()))
                .isZero();
    }

    @Test
    void 재발급하면_새_토큰을_주고_이전_토큰은_ROTATED가_된다() {
        long userId = createUser();
        TokenPair first = service.startSession(userId, "d", null, Platform.ANDROID);

        TokenPair second = service.refresh(first.refreshToken());

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(statuses(first.sessionId())).containsExactlyInAnyOrder("ROTATED", "ACTIVE");
        // 새 토큰으로 다시 재발급할 수 있다
        assertThat(service.refresh(second.refreshToken()).sessionId()).isEqualTo(first.sessionId());
    }

    @Test
    void 교체된_Refresh가_다시_오면_탈취로_보고_그_사용자의_모든_세션을_폐기한다() {
        long userId = createUser();
        long otherUserId = createUser();
        TokenPair phone = service.startSession(userId, "phone", null, Platform.ANDROID);
        TokenPair tablet = service.startSession(userId, "tablet", null, Platform.ANDROID);
        TokenPair otherUser = service.startSession(otherUserId, "phone", null, Platform.ANDROID);
        TokenPair rotated = service.refresh(phone.refreshToken());

        // 탈취된 이전 토큰 재사용
        assertThatThrownBy(() -> service.refresh(phone.refreshToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID);

        assertThat(revokedAt(phone.sessionId())).isTrue();
        assertThat(revokedAt(tablet.sessionId())).isTrue();
        assertThat(statuses(phone.sessionId())).doesNotContain("ACTIVE");
        assertThat(statuses(tablet.sessionId())).doesNotContain("ACTIVE");
        assertThat(revocationStore.isRevoked(phone.sessionId())).isTrue();
        assertThat(revocationStore.isRevoked(tablet.sessionId())).isTrue();
        // 정상 사용자가 받은 새 토큰도 더는 쓸 수 없다 → 재로그인
        assertThatThrownBy(() -> service.refresh(rotated.refreshToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.refresh(tablet.refreshToken())).isInstanceOf(BusinessException.class);
        // 다른 사용자는 영향 없음
        assertThat(revokedAt(otherUser.sessionId())).isFalse();
        assertThat(service.refresh(otherUser.refreshToken())).isNotNull();
    }

    @Test
    void 로그아웃된_기기의_Refresh는_거절하지만_다른_기기는_그대로_둔다() {
        long userId = createUser();
        TokenPair phone = service.startSession(userId, "phone", null, Platform.ANDROID);
        TokenPair tablet = service.startSession(userId, "tablet", null, Platform.ANDROID);
        service.revokeSession(phone.sessionId());

        assertThatThrownBy(() -> service.refresh(phone.refreshToken())).isInstanceOf(BusinessException.class);

        assertThat(revokedAt(tablet.sessionId())).isFalse();
        assertThat(service.refresh(tablet.refreshToken()).sessionId()).isEqualTo(tablet.sessionId());
    }

    @Test
    void 만료된_Refresh는_거절한다() {
        long userId = createUser();
        TokenPair tokens = service.startSession(userId, "d", null, Platform.ANDROID);
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE session_id = ?",
                tokens.sessionId());

        assertThatThrownBy(() -> service.refresh(tokens.refreshToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID);
    }

    @Test
    void 모르는_값이나_빈_값은_거절한다() {
        assertThatThrownBy(() -> service.refresh("unknown-token")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.refresh("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 세션_폐기는_여러_번_해도_안전하다() {
        long userId = createUser();
        TokenPair tokens = service.startSession(userId, "d", null, Platform.ANDROID);

        service.revokeSession(tokens.sessionId());
        service.revokeSession(tokens.sessionId());
        service.revokeSession(Long.MAX_VALUE);

        assertThat(revokedAt(tokens.sessionId())).isTrue();
        assertThat(statuses(tokens.sessionId())).containsExactly("REVOKED");
    }

    @Test
    void 토큰_묶음을_문자열로_찍어도_토큰_값이_나오지_않는다() {
        TokenPair tokens = service.startSession(createUser(), "d", null, Platform.ANDROID);

        assertThat(tokens.toString()).doesNotContain(tokens.accessToken()).doesNotContain(tokens.refreshToken());
    }

    private List<String> statuses(long sessionId) {
        return jdbcTemplate.queryForList("SELECT status FROM refresh_tokens WHERE session_id = ?", String.class,
                sessionId);
    }

    private boolean revokedAt(long sessionId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id = ?", Boolean.class, sessionId));
    }
}
