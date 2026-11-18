-- 사용자 단위 알림 설정 (사양서 4.1 SET-02). 그룹별 알림 끄기는 group_members.notif_muted (V3)
-- 행이 없으면 기본값(미리보기 보이기, 방해 금지 꺼짐)으로 본다. 방해 금지 시각은 Asia/Seoul 기준
CREATE TABLE user_notification_settings (
    user_id        BIGINT      PRIMARY KEY REFERENCES users (id),
    preview_hidden BOOLEAN     NOT NULL DEFAULT false,
    quiet_enabled  BOOLEAN     NOT NULL DEFAULT false,
    quiet_start    TIME        NOT NULL DEFAULT '22:00',
    quiet_end      TIME        NOT NULL DEFAULT '07:00',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_notification_settings_quiet CHECK (quiet_start <> quiet_end)
);
