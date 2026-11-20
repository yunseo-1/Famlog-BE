-- V13: 신고·최소 관리자 도구 (사양서 4.2 MOD-01, 10.2 reports·app_versions·feature_flags, 14장 강제 업데이트)

-- 신고. 대상은 신고한 사람이 속한 그룹의 것만 받는다 (그룹은 서버가 대상에서 정한다)
CREATE TABLE reports (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id  BIGINT        NOT NULL REFERENCES users (id),
    group_id     BIGINT        NOT NULL REFERENCES family_groups (id),
    target_type  VARCHAR(10)   NOT NULL,
    target_id    BIGINT        NOT NULL,
    reason       VARCHAR(20)   NOT NULL,
    detail       VARCHAR(500),
    status       VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    action       VARCHAR(20),                                  -- 조치: HIDE_MESSAGE, DISMISS_CANDIDATE
    handler_note VARCHAR(500),                                 -- 관리자 메모 (신고 내용·본문은 넣지 않는다)
    handled_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_reports_target_type CHECK (target_type IN ('MESSAGE', 'MEDIA', 'AI_OUTPUT', 'USER')),
    CONSTRAINT ck_reports_reason CHECK (reason IN ('SPAM', 'HARASSMENT', 'SEXUAL', 'VIOLENCE', 'CHILD_SAFETY',
                                                   'PRIVACY', 'AI_INACCURATE', 'OTHER')),
    CONSTRAINT ck_reports_status CHECK (status IN ('OPEN', 'REVIEWED', 'ACTIONED', 'REJECTED')),
    CONSTRAINT ck_reports_action CHECK (action IS NULL OR action IN ('HIDE_MESSAGE', 'DISMISS_CANDIDATE'))
);
CREATE INDEX ix_reports_status ON reports (status, id);
-- 같은 사람이 같은 대상을 처리 전에 다시 신고하면 한 건으로 합친다
CREATE UNIQUE INDEX uq_reports_open_target ON reports (reporter_id, target_type, target_id) WHERE status = 'OPEN';

-- 플랫폼별 최소 지원·최신 버전과 공지 (GET /app/config, 최소 버전보다 낮으면 426)
CREATE TABLE app_versions (
    platform                VARCHAR(10)  PRIMARY KEY,
    min_supported_version   VARCHAR(20)  NOT NULL,
    latest_version          VARCHAR(20),
    notice                  VARCHAR(500),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_versions_platform CHECK (platform IN ('ANDROID', 'IOS'))
);
INSERT INTO app_versions (platform, min_supported_version) VALUES ('ANDROID', '0.0.0');

-- 기능 플래그. 행이 없으면 설정값(application.yml)을 따른다
CREATE TABLE feature_flags (
    key        VARCHAR(50)  PRIMARY KEY,
    enabled    BOOLEAN      NOT NULL,
    rules      JSONB,                                          -- 그룹·버전별 규칙 (v1.1)
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
