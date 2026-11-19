-- V9: AI 대화 분석 트리거·요청 기록, 그룹 AI 분석 설정 (사양서 4.5 ARC-01, 4.1 SET-01, 6.2 ①, 7.2, 8.7)
-- 프롬프트·응답 원문은 어디에도 저장하지 않는다 (8.7). 등록 후보 테이블은 S18에서 만든다

-- 그룹 설정 (SET-01). 행이 없으면 기본값(AI 분석 꺼짐)
CREATE TABLE group_settings (
    group_id            BIGINT      PRIMARY KEY REFERENCES family_groups (id),
    ai_analysis_enabled BOOLEAN     NOT NULL DEFAULT false,
    ai_enabled_by       BIGINT REFERENCES users (id),       -- 마지막으로 켠 관리자
    ai_enabled_at       TIMESTAMPTZ,                         -- 마지막으로 켠 시각. 이전 메시지는 분석하지 않는다
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 개인 분석 제외를 마지막으로 바꾼 시각. 제외했던 동안의 메시지는 다시 켜도 보내지 않는다
ALTER TABLE group_members ADD COLUMN ai_opt_out_changed_at TIMESTAMPTZ;

-- 어디까지 분석 대상으로 넘겼는지 (이 seq 까지는 다시 보지 않는다). API 의 AI 트리거만 쓴다
ALTER TABLE chat_rooms ADD COLUMN ai_analyzed_seq BIGINT NOT NULL DEFAULT 0;

-- 워커에 보낸 분석 요청 (LLM 호출 기록, AR-10). 모델·프롬프트 버전, 토큰 수, 지연 시간, 결과 상태만 남긴다
CREATE TABLE ai_jobs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id          BIGINT       NOT NULL REFERENCES family_groups (id),
    room_id           BIGINT       NOT NULL REFERENCES chat_rooms (id),
    from_seq          BIGINT       NOT NULL,                -- 분석 구간 (from_seq, to_seq]
    to_seq            BIGINT       NOT NULL,
    trigger           VARCHAR(10)  NOT NULL,                -- RULE(규칙 필터 + 30초 멈춤), BATCH(30개·10분)
    message_count     INTEGER      NOT NULL,                -- 분석 구간에서 보낸 메시지 수 (앞 맥락 제외)
    member_refs       JSONB        NOT NULL,                -- 임시 ID → 사용자 ID {"M1": 12}. 응답을 실제 ID로 바꿀 때 쓴다
    status            VARCHAR(10)  NOT NULL DEFAULT 'QUEUED',
    error_code        VARCHAR(30),
    model_version     VARCHAR(50),
    prompt_version    VARCHAR(30),
    input_tokens      INTEGER,
    output_tokens     INTEGER,
    latency_ms        INTEGER,
    candidate_count   INTEGER,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_jobs_trigger CHECK (trigger IN ('RULE', 'BATCH')),
    CONSTRAINT ck_ai_jobs_status CHECK (status IN ('QUEUED', 'DONE', 'FAILED')),
    CONSTRAINT ck_ai_jobs_range CHECK (to_seq > from_seq),
    CONSTRAINT ck_ai_jobs_message_count CHECK (message_count > 0)
);
CREATE INDEX ix_ai_jobs_group_created ON ai_jobs (group_id, created_at);
