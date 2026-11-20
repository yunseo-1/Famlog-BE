-- V14: 공지를 바꾼 시각 (앱이 이미 본 공지를 다시 띄우지 않게, GET /app/config noticeUpdatedAt)
ALTER TABLE app_versions ADD COLUMN notice_updated_at TIMESTAMPTZ;
