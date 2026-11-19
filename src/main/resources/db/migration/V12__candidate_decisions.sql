-- V12: 후보 승인·무시·되돌리기 (사양서 6.2 ③~⑤, 10.2 item_sources·suggestion_feedback)

-- 등록 항목의 원문 연결 (ARC-06 "원문 보기"). 항목을 지우면 서비스가 함께 지운다 (여러 테이블을 가리켜 FK 없음)
CREATE TABLE item_sources (
    entity_type VARCHAR(20) NOT NULL,                       -- SCHEDULE(기념일 포함), TODO, PLACE
    entity_id   BIGINT      NOT NULL,
    message_id  BIGINT      NOT NULL REFERENCES messages (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_id, message_id),
    CONSTRAINT ck_item_sources_type CHECK (entity_type IN ('SCHEDULE', 'TODO', 'PLACE'))
);

-- 후보 처리 기록 (사양서 7.3 수락률·수정 없이 저장 비율·오탐률)
CREATE TABLE suggestion_feedback (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id   BIGINT        NOT NULL REFERENCES registration_candidates (id) ON DELETE CASCADE,
    user_id        BIGINT        NOT NULL REFERENCES users (id),
    action         VARCHAR(10)   NOT NULL,
    edited_fields  VARCHAR(30)[] NOT NULL DEFAULT '{}',      -- EDITED 일 때 고친 항목 (값은 남기지 않는다)
    dismiss_reason VARCHAR(20),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_feedback_action CHECK (action IN ('APPROVED', 'EDITED', 'DISMISSED', 'UNDONE')),
    CONSTRAINT ck_feedback_reason CHECK (dismiss_reason IS NULL
        OR dismiss_reason IN ('ALREADY_REGISTERED', 'JOKE', 'WRONG'))
);
CREATE INDEX ix_feedback_candidate ON suggestion_feedback (candidate_id);

-- 무시 사유 (선택), 장소 후보를 이미 공유된 장소에 연결한 경우 created_entity_type = 'PLACE_LINKED'
ALTER TABLE registration_candidates ADD CONSTRAINT ck_candidates_dismiss_reason CHECK (dismiss_reason IS NULL
    OR dismiss_reason IN ('ALREADY_REGISTERED', 'JOKE', 'WRONG'));
ALTER TABLE registration_candidates ADD CONSTRAINT ck_candidates_entity_type CHECK (created_entity_type IS NULL
    OR created_entity_type IN ('SCHEDULE', 'TODO', 'PLACE', 'PLACE_LINKED'));
