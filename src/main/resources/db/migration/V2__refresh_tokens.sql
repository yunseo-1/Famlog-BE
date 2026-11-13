-- V2: Refresh 토큰 이력 (사양서 9.2 재사용 감지)
-- 교체된(ROTATED) 토큰을 만료 때까지 남겨, 다시 쓰이면 탈취로 보고 사용자 전 세션을 폐기한다.
-- user_sessions.refresh_token_hash 는 이 테이블로 옮긴다 (2026-11-13 결정, 사양서 10.2 갱신 필요).

CREATE TABLE refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES user_sessions (id),
    token_hash VARCHAR(64) NOT NULL,                -- SHA-256 hex. 원문은 저장하지 않는다
    status     VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);
-- 세션당 사용 중인 토큰은 하나뿐이다
CREATE UNIQUE INDEX uq_refresh_tokens_active_per_session ON refresh_tokens (session_id) WHERE status = 'ACTIVE';

ALTER TABLE user_sessions DROP COLUMN refresh_token_hash;
