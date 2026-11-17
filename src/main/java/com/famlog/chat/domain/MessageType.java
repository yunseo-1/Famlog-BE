package com.famlog.chat.domain;

/** 메시지 종류 (사양서 10.2 messages.type). 지금 앱이 보낼 수 있는 것은 TEXT, SYSTEM은 서버만 만든다. 사진·동영상은 S9. */
public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    PLACE,
    SYSTEM,
    CANDIDATE,
    POLL
}
