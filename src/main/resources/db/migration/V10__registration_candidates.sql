-- V10: 등록 후보 (사양서 6장, 10.2 registration_candidates·candidate_evidence)
-- AI 인식 결과는 여기에만 저장한다. 실제 일정·할 일·장소는 승인 API(S19)에서만 만든다 (6장 핵심 원칙)
-- 승인·무시·되돌리기 컬럼(decided_*, dismiss_reason, created_entity_*, target_ref)은 S19에서 채운다

CREATE TABLE registration_candidates (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id            BIGINT           NOT NULL REFERENCES family_groups (id),
    room_id             BIGINT           NOT NULL REFERENCES chat_rooms (id),
    job_id              BIGINT           NOT NULL REFERENCES ai_jobs (id),   -- 처음 만든 분석 요청
    type                VARCHAR(12)      NOT NULL,
    status              VARCHAR(10)      NOT NULL DEFAULT 'PENDING',
    fields              JSONB            NOT NULL,                -- 정규화한 값 (HANDOFF "후보 생성 규칙")
    inferred_fields     VARCHAR(30)[]    NOT NULL DEFAULT '{}',   -- 서버가 추정한 항목 ("date", "startTime.ampm" …)
    missing_fields      VARCHAR(30)[]    NOT NULL DEFAULT '{}',   -- 채워야 등록되는 필수 항목
    confidence          DOUBLE PRECISION NOT NULL,                -- 0.75 이상 채팅방 카드, 0.5~0.75 제안함 (0.5 미만은 저장하지 않는다)
    dedup_key           VARCHAR(300)     NOT NULL,                -- 유형 + 정규화 제목 + 날짜 (장소는 카카오 장소 ID)
    target_ref          JSONB,                                    -- 기존 항목 수정·완료 후보의 대상 (S19)
    decided_by          BIGINT REFERENCES users (id),
    decided_at          TIMESTAMPTZ,
    dismiss_reason      VARCHAR(20),
    created_entity_type VARCHAR(20),
    created_entity_id   BIGINT,
    model_version       VARCHAR(50),
    prompt_version      VARCHAR(30),
    expires_at          TIMESTAMPTZ      NOT NULL,                -- 일정은 일정 시각, 나머지는 만든 뒤 7일
    version             BIGINT           NOT NULL DEFAULT 0,      -- 낙관적 잠금 (먼저 처리한 사람이 반영된다)
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_candidates_type CHECK (type IN ('SCHEDULE', 'ANNIVERSARY', 'TODO', 'PLACE')),
    CONSTRAINT ck_candidates_status CHECK (status IN ('PENDING', 'APPROVED', 'DISMISSED', 'EXPIRED', 'UNDONE')),
    CONSTRAINT ck_candidates_confidence CHECK (confidence >= 0.5 AND confidence <= 1)
);
CREATE INDEX ix_candidates_group_dedup ON registration_candidates (group_id, dedup_key);

-- 후보의 근거 메시지. 같은 내용을 다시 말하면 근거만 늘어난다
CREATE TABLE candidate_evidence (
    candidate_id BIGINT      NOT NULL REFERENCES registration_candidates (id) ON DELETE CASCADE,
    message_id   BIGINT      NOT NULL REFERENCES messages (id),
    spans        JSONB       NOT NULL DEFAULT '[]',               -- 추출한 부분 (원문 강조용)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (candidate_id, message_id)
);
CREATE INDEX ix_candidate_evidence_message ON candidate_evidence (message_id);
