-- V1: 계정·보안 테이블 (사양서 10.2, 8.2)
-- 이메일·실명·전화번호는 저장하지 않는다 (사양서 8.2).
-- 시각은 timestamptz(UTC), enum 값은 varchar + CHECK 제약.

CREATE TABLE users (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nickname          VARCHAR(30)  NOT NULL,
    profile_image_key VARCHAR(255),
    birth_ym          CHAR(6),                         -- YYYYMM. 연령 확인(AUTH-02) 전에는 NULL
    age_verified_at   TIMESTAMPTZ,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_group_id     BIGINT,                          -- FK는 family_groups 생성(S5) 마이그레이션에서 추가
    ui_mode           VARCHAR(10)  NOT NULL DEFAULT 'DEFAULT',
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_DELETION', 'DELETED')),
    CONSTRAINT ck_users_ui_mode CHECK (ui_mode IN ('DEFAULT', 'SENIOR')),
    CONSTRAINT ck_users_birth_ym CHECK (birth_ym ~ '^[0-9]{4}(0[1-9]|1[0-2])$')
);

CREATE TABLE social_accounts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id),
    provider         VARCHAR(10)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_social_accounts_provider CHECK (provider IN ('KAKAO', 'GOOGLE', 'APPLE')),
    CONSTRAINT uq_social_accounts_provider_user UNIQUE (provider, provider_user_id)
);
CREATE INDEX ix_social_accounts_user ON social_accounts (user_id);

-- Refresh 토큰은 해시만 저장한다 (사양서 9.2)
CREATE TABLE user_sessions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT       NOT NULL REFERENCES users (id),
    device_id          VARCHAR(100) NOT NULL,
    device_name        VARCHAR(100),
    platform           VARCHAR(10)  NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    last_used_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_sessions_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT uq_user_sessions_refresh_token_hash UNIQUE (refresh_token_hash)
);
CREATE INDEX ix_user_sessions_user ON user_sessions (user_id);

CREATE TABLE device_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    session_id  BIGINT       NOT NULL REFERENCES user_sessions (id),
    platform    VARCHAR(10)  NOT NULL,
    fcm_token   VARCHAR(512) NOT NULL,
    app_version VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_device_tokens_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT uq_device_tokens_fcm_token UNIQUE (fcm_token)
);
CREATE INDEX ix_device_tokens_user ON device_tokens (user_id);
CREATE INDEX ix_device_tokens_session ON device_tokens (session_id);

-- 동의 이력: 철회해도 행을 지우지 않고 withdrawn_at 을 채운다 (사양서 8.3 동의 증빙)
CREATE TABLE consents (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    type         VARCHAR(30) NOT NULL,
    doc_version  VARCHAR(20) NOT NULL,
    agreed_at    TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_consents_type CHECK (type IN ('TERMS', 'PRIVACY', 'AI_ANALYSIS', 'OVERSEAS_TRANSFER', 'LOCATION'))
);
CREATE INDEX ix_consents_user_type ON consents (user_id, type);
