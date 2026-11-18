-- V7: 일정·할 일·공휴일 (사양서 4.3 SCH-01~05, TODO-01, 10.2)
-- 시각은 UTC(timestamptz). 종일 일정은 서울 시각 시작일 00:00 ~ 끝날 다음 날 00:00 으로 저장한다

CREATE TABLE schedules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT        NOT NULL REFERENCES family_groups (id),
    title           VARCHAR(100)  NOT NULL,
    starts_at       TIMESTAMPTZ   NOT NULL,
    ends_at         TIMESTAMPTZ   NOT NULL,
    all_day         BOOLEAN       NOT NULL DEFAULT false,
    rrule           VARCHAR(200),                       -- RFC 5545 RRULE (FREQ=WEEKLY|MONTHLY|YEARLY, INTERVAL, UNTIL|COUNT)
    calendar_type   VARCHAR(10)   NOT NULL DEFAULT 'SOLAR',
    lunar_month_day CHAR(4),                            -- 음력 MMDD (v1.1 DIF-04, 지금은 쓰지 않는다)
    place_id        BIGINT,                             -- 장소 연결. FK 는 places 를 만드는 S14 에서
    memo            VARCHAR(1000),
    -- 리마인드 (SCH-03): PREV_DAY_20(전날 20:00), MIN_10, MIN_30, HOUR_1, DAY_1. 빈 배열 = 알림 없음
    reminders       VARCHAR(20)[] NOT NULL DEFAULT '{PREV_DAY_20,HOUR_1}',
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedules_time CHECK (ends_at >= starts_at),
    CONSTRAINT ck_schedules_calendar_type CHECK (calendar_type IN ('SOLAR', 'LUNAR')),
    CONSTRAINT ck_schedules_lunar_month_day CHECK (lunar_month_day ~ '^(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|30)$')
);
CREATE INDEX ix_schedules_group_starts ON schedules (group_id, starts_at);
-- 반복 일정은 시작 시각과 상관없이 매번 펼쳐 봐야 한다
CREATE INDEX ix_schedules_recurring ON schedules (group_id) WHERE rrule IS NOT NULL;

-- 참여 구성원. 나중에 그룹을 나간 사람도 기록으로 남긴다 (알림만 보내지 않는다)
CREATE TABLE schedule_participants (
    schedule_id BIGINT NOT NULL REFERENCES schedules (id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users (id),
    PRIMARY KEY (schedule_id, user_id)
);
CREATE INDEX ix_schedule_participants_user ON schedule_participants (user_id);

-- 보낸 리마인드. 서버가 여러 대여도 회차·종류마다 한 번만 보내도록 먼저 넣은 서버만 보낸다
CREATE TABLE schedule_reminder_sends (
    schedule_id      BIGINT      NOT NULL REFERENCES schedules (id) ON DELETE CASCADE,
    occurrence_start TIMESTAMPTZ NOT NULL,
    kind             VARCHAR(20) NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (schedule_id, occurrence_start, kind)
);

CREATE TABLE todos (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES family_groups (id),
    kind        VARCHAR(10)  NOT NULL DEFAULT 'TASK',   -- SHOPPING 은 v1.1 (TODO-02)
    title       VARCHAR(100) NOT NULL,
    assignee_id BIGINT REFERENCES users (id),
    due_date    DATE,
    done_at     TIMESTAMPTZ,
    done_by     BIGINT REFERENCES users (id),
    created_by  BIGINT       NOT NULL REFERENCES users (id),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_todos_kind CHECK (kind IN ('TASK', 'SHOPPING')),
    CONSTRAINT ck_todos_done CHECK ((done_at IS NULL) = (done_by IS NULL))
);
CREATE INDEX ix_todos_group_done_due ON todos (group_id, done_at, due_date);

-- 대한민국 공휴일·대체공휴일 (SCH-04). 해마다 다음 해 데이터를 마이그레이션으로 넣는다 (자동 갱신·관리자 수정은 S20)
CREATE TABLE holidays (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    date          DATE        NOT NULL,
    name          VARCHAR(30) NOT NULL,
    is_substitute BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_holidays_date_name UNIQUE (date, name)
);

-- 2026~2027년. 우주항공청 월력요항과 맞춰 확인한다
INSERT INTO holidays (date, name, is_substitute) VALUES
    ('2026-01-01', '신정', false),
    ('2026-02-16', '설날 연휴', false),
    ('2026-02-17', '설날', false),
    ('2026-02-18', '설날 연휴', false),
    ('2026-03-01', '삼일절', false),
    ('2026-03-02', '대체공휴일(삼일절)', true),
    ('2026-05-05', '어린이날', false),
    ('2026-05-24', '부처님오신날', false),
    ('2026-05-25', '대체공휴일(부처님오신날)', true),
    ('2026-06-03', '전국동시지방선거', false),
    ('2026-06-06', '현충일', false),
    ('2026-08-15', '광복절', false),
    ('2026-08-17', '대체공휴일(광복절)', true),
    ('2026-09-24', '추석 연휴', false),
    ('2026-09-25', '추석', false),
    ('2026-09-26', '추석 연휴', false),
    ('2026-10-03', '개천절', false),
    ('2026-10-05', '대체공휴일(개천절)', true),
    ('2026-10-09', '한글날', false),
    ('2026-12-25', '기독탄신일', false),
    ('2027-01-01', '신정', false),
    ('2027-02-06', '설날', false),
    ('2027-02-05', '설날 연휴', false),
    ('2027-02-07', '설날 연휴', false),
    ('2027-02-08', '대체공휴일(설날)', true),
    ('2027-03-01', '삼일절', false),
    ('2027-05-05', '어린이날', false),
    ('2027-05-13', '부처님오신날', false),
    ('2027-06-06', '현충일', false),
    ('2027-08-15', '광복절', false),
    ('2027-08-16', '대체공휴일(광복절)', true),
    ('2027-09-14', '추석 연휴', false),
    ('2027-09-15', '추석', false),
    ('2027-09-16', '추석 연휴', false),
    ('2027-10-03', '개천절', false),
    ('2027-10-04', '대체공휴일(개천절)', true),
    ('2027-10-09', '한글날', false),
    ('2027-10-11', '대체공휴일(한글날)', true),
    ('2027-12-25', '기독탄신일', false),
    ('2027-12-27', '대체공휴일(기독탄신일)', true);
