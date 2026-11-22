-- V17: 삭제 예정 그룹 파기 (사양서 2.2 그룹 삭제, 8.2 "그룹 삭제 시" 파기)
-- 유예가 지나면 배치가 그룹 데이터를 모두 지우고, 신고·감사 로그가 가리키는 그룹 행만 DELETED 로 남긴다 (이름·사진 없음)

ALTER TABLE family_groups DROP CONSTRAINT ck_family_groups_status;
ALTER TABLE family_groups ADD CONSTRAINT ck_family_groups_status
    CHECK (status IN ('ACTIVE', 'PENDING_DELETION', 'DELETED'));
ALTER TABLE family_groups ADD COLUMN purged_at TIMESTAMPTZ;   -- 파기한 시각
CREATE INDEX ix_family_groups_purge_due ON family_groups (delete_scheduled_at) WHERE status = 'PENDING_DELETION';

-- 보관 기간 정리 배치가 쓰는 인덱스
CREATE INDEX ix_media_assets_unattached ON media_assets (created_at) WHERE message_id IS NULL;
CREATE INDEX ix_location_access_logs_accessed ON location_access_logs (accessed_at);
CREATE INDEX ix_candidates_created ON registration_candidates (created_at);
