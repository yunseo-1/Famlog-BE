-- V16: 내 데이터 내보내기 요청 (사양서 4.1 ACC-02, 8.4 열람·전송권, 10.2 data_export_requests)
-- 요청 → 서버 배치가 ZIP(JSON + 원본 링크)을 만들어 S3에 올림 → 알림 → 7일 뒤 파일 삭제

CREATE TABLE data_export_requests (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT        NOT NULL REFERENCES users (id),
    status       VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED',
    attempts     INT           NOT NULL DEFAULT 0,          -- 만든 횟수 (실패하면 다시 시도, 3번이면 FAILED)
    storage_key  VARCHAR(300),                              -- ZIP 객체 키 (만료·파기하면 지운다)
    size_bytes   BIGINT,
    counts       JSONB,                                     -- 항목별 건수만 (내용은 넣지 않는다)
    requested_at TIMESTAMPTZ   NOT NULL,
    started_at   TIMESTAMPTZ,                               -- 마지막으로 만들기 시작한 시각 (오래 멈춘 작업을 다시 잡는다)
    ready_at     TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,                               -- 이 시각에 ZIP 과 원본 링크가 함께 만료된다
    downloaded_at TIMESTAMPTZ,                             -- 처음 다운로드 링크를 받은 시각 (감사 로그는 처음 한 번)
    finished_at  TIMESTAMPTZ,                               -- FAILED·EXPIRED 가 된 시각
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_export_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'READY', 'FAILED', 'EXPIRED'))
);
-- 사용자당 진행 중인 요청은 하나
CREATE UNIQUE INDEX uq_data_export_open ON data_export_requests (user_id) WHERE status IN ('REQUESTED', 'PROCESSING');
CREATE INDEX ix_data_export_user ON data_export_requests (user_id, id DESC);
CREATE INDEX ix_data_export_pending ON data_export_requests (id) WHERE status IN ('REQUESTED', 'PROCESSING');
CREATE INDEX ix_data_export_expiry ON data_export_requests (expires_at) WHERE status = 'READY';
