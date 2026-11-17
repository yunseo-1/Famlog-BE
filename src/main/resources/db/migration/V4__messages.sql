-- 채팅 메시지 (사양서 10.2 messages, 4.2 CHAT-01~04·06)

-- 방별 순번(seq) 발급기. 보낼 때 이 행을 잠그고 +1 해서 같은 방 안에서 겹치지 않고 빠짐없이 매긴다
ALTER TABLE chat_rooms ADD COLUMN last_seq BIGINT NOT NULL DEFAULT 0;

-- 들어온 시점의 seq. 구성원은 이 값보다 큰 seq의 메시지만 본다 (입장 이후 대화만 공개)
ALTER TABLE group_members ADD COLUMN join_seq BIGINT NOT NULL DEFAULT 0;

CREATE TABLE messages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id           BIGINT      NOT NULL REFERENCES chat_rooms (id),
    seq               BIGINT      NOT NULL,
    sender_id         BIGINT REFERENCES users (id),          -- NULL = 시스템 메시지
    client_message_id UUID,                                  -- 앱이 만든 ID. 재전송 멱등 처리 기준
    type              VARCHAR(20) NOT NULL,
    body              TEXT,                                  -- 삭제되면 NULL
    payload           JSONB,
    reply_to_id       BIGINT REFERENCES messages (id),
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_messages_type CHECK (type IN
        ('TEXT', 'IMAGE', 'VIDEO', 'VOICE', 'PLACE', 'SYSTEM', 'CANDIDATE', 'POLL')),
    CONSTRAINT ck_messages_seq CHECK (seq > 0),
    CONSTRAINT uq_messages_room_seq UNIQUE (room_id, seq),
    CONSTRAINT uq_messages_sender_client UNIQUE (sender_id, client_message_id)
);

-- 공감: 한 사람이 메시지 하나에 하나. 다시 고르면 바뀐다
CREATE TABLE message_reactions (
    message_id BIGINT      NOT NULL REFERENCES messages (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    emoji      VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id),
    CONSTRAINT ck_message_reactions_emoji CHECK (emoji IN ('HEART', 'LIKE', 'LAUGH', 'WOW', 'SAD', 'PRAY'))
);
