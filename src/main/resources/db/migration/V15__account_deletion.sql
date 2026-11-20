-- V15: 계정 삭제 요청 (사양서 4.1 ACC-01, 8.9 파기 절차, 10.2 account_deletion_requests)
-- 요청 → 즉시 로그아웃·비활성 → 30일 유예(복구 가능) → 배치가 개인정보 파기·작성자 익명화

CREATE TABLE account_deletion_requests (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users (id),
    source              VARCHAR(10)  NOT NULL,                  -- APP(앱), ADMIN(웹 요청을 개발자가 대신 처리)
    delete_media        BOOLEAN      NOT NULL DEFAULT false,    -- 내가 올린 사진·동영상도 지운다
    scheduled_group_ids BIGINT[]     NOT NULL DEFAULT '{}',     -- 함께 삭제 예정으로 돌린 혼자인 그룹 (복구하면 되살린다)
    status              VARCHAR(10)  NOT NULL DEFAULT 'REQUESTED',
    requested_at        TIMESTAMPTZ  NOT NULL,
    purge_after         TIMESTAMPTZ  NOT NULL,                  -- 이 시각이 지나면 파기 배치가 처리한다
    completed_at        TIMESTAMPTZ,                            -- 복구했거나 파기한 시각
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_deletion_source CHECK (source IN ('APP', 'ADMIN')),
    CONSTRAINT ck_account_deletion_status CHECK (status IN ('REQUESTED', 'CANCELLED', 'COMPLETED'))
);
-- 사용자당 진행 중인 요청은 하나
CREATE UNIQUE INDEX uq_account_deletion_open ON account_deletion_requests (user_id) WHERE status = 'REQUESTED';
CREATE INDEX ix_account_deletion_due ON account_deletion_requests (purge_after) WHERE status = 'REQUESTED';
