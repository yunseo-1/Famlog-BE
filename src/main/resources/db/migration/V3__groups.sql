-- V3: 가족 그룹·구성원·초대, 채팅방 자리, 감사 로그 (사양서 2장, 4.1 GRP-01~05, 9.8, 10.2)

CREATE TABLE family_groups (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                VARCHAR(30)  NOT NULL,
    image_key           VARCHAR(255),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    delete_scheduled_at TIMESTAMPTZ,                  -- 삭제 예정 시각 (30일 유예). 실제 파기는 배치(S20)
    created_by          BIGINT       NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_family_groups_status CHECK (status IN ('ACTIVE', 'PENDING_DELETION'))
);

CREATE TABLE group_members (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id       BIGINT      NOT NULL REFERENCES family_groups (id),
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    role           VARCHAR(10) NOT NULL,
    family_role    VARCHAR(20),                         -- NULL = 아직 설정 전 → 앱이 SCR-03으로 안내
    family_title   VARCHAR(20),                         -- 호칭 (큰아들, 막내)
    calendar_color VARCHAR(7)  NOT NULL,
    ai_opt_out     BOOLEAN     NOT NULL DEFAULT false,
    last_read_seq  BIGINT      NOT NULL DEFAULT 0,
    notif_muted    BOOLEAN     NOT NULL DEFAULT false,
    joined_at      TIMESTAMPTZ NOT NULL,
    left_at        TIMESTAMPTZ,                         -- 나감·내보내짐. 다시 들어오면 NULL로 되돌린다
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_group_members_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_group_members_family_role CHECK (family_role IN
        ('FATHER', 'MOTHER', 'SON', 'DAUGHTER', 'GRANDFATHER', 'GRANDMOTHER', 'OTHER')),
    CONSTRAINT ck_group_members_calendar_color CHECK (calendar_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT uq_group_members_group_user UNIQUE (group_id, user_id)
);
-- 그룹당 활성 OWNER는 한 명
CREATE UNIQUE INDEX uq_group_members_one_owner ON group_members (group_id) WHERE role = 'OWNER' AND left_at IS NULL;
CREATE INDEX ix_group_members_user_active ON group_members (user_id) WHERE left_at IS NULL;

CREATE TABLE group_invites (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES family_groups (id),
    code       VARCHAR(8)  NOT NULL,
    created_by BIGINT      NOT NULL REFERENCES users (id),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,                             -- 재발급·그룹 삭제 시
    use_count  INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_invites_code UNIQUE (code)
);
CREATE INDEX ix_group_invites_group ON group_invites (group_id);

-- 그룹당 단체 채팅방 1개 (1:1 채팅 없음). 메시지 관련 컬럼은 CHAT(S8)에서 추가한다
CREATE TABLE chat_rooms (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES family_groups (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_rooms_group UNIQUE (group_id)
);

-- 감사 로그 (사양서 9.8, 1년 보관). 대상이 지워져도 기록은 남겨야 하므로 외래키를 걸지 않는다
CREATE TABLE audit_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT,
    action      VARCHAR(50) NOT NULL,
    target_type VARCHAR(30),
    target_id   BIGINT,
    group_id    BIGINT,
    meta        JSONB,
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_group ON audit_logs (group_id, created_at);
CREATE INDEX ix_audit_logs_created ON audit_logs (created_at);

ALTER TABLE users ADD CONSTRAINT fk_users_last_group FOREIGN KEY (last_group_id) REFERENCES family_groups (id);
