-- 사진·동영상 (사양서 10.2 media_assets, 4.2 CHAT-05, 8.6 위치정보)

CREATE TABLE media_assets (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id          BIGINT       NOT NULL REFERENCES family_groups (id),
    uploader_id       BIGINT       NOT NULL REFERENCES users (id),
    message_id        BIGINT REFERENCES messages (id),        -- 메시지로 보내기 전에는 NULL
    media_type        VARCHAR(10)  NOT NULL,
    mime              VARCHAR(50)  NOT NULL,                  -- 업로드 전: 앱이 신고한 형식 / 완료 후: 파일에서 확인한 형식
    size              BIGINT       NOT NULL,
    storage_key       VARCHAR(200) NOT NULL,                  -- 추측할 수 없는 UUID 키 (사양서 9.1)
    variants          JSONB,                                  -- 썸네일 키 {"THUMB_S": "...", "THUMB_M": "...", "THUMB_L": "..."}
    width             INTEGER,
    height            INTEGER,
    duration_sec      INTEGER,
    taken_at          TIMESTAMPTZ,
    location          GEOGRAPHY(Point, 4326),                 -- "사진 위치 사용"에 동의한 경우만 (기본 NULL)
    status            VARCHAR(20)  NOT NULL,                  -- 업로드·처리 상태 (사양서 scan_status)
    reject_reason     VARCHAR(30),
    phash             VARCHAR(16),                            -- 앨범 단계에서 채운다
    blur_score        REAL,
    upload_expires_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_media_assets_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_media_assets_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'READY', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_media_assets_size CHECK (size > 0),
    CONSTRAINT uq_media_assets_storage_key UNIQUE (storage_key)
);
CREATE INDEX ix_media_assets_message ON media_assets (message_id);
CREATE INDEX ix_media_assets_group_created ON media_assets (group_id, created_at);

-- 개인위치정보 이용·제공 사실 확인자료 (사양서 8.6, 위치정보법). 좌표는 남기지 않는다
CREATE TABLE location_access_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    purpose     VARCHAR(20) NOT NULL,
    target_id   BIGINT,                                       -- PHOTO_LOCATION: media_assets.id
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_location_access_logs_purpose CHECK (purpose IN ('NEARBY', 'PHOTO_LOCATION'))
);
CREATE INDEX ix_location_access_logs_user ON location_access_logs (user_id, accessed_at);
