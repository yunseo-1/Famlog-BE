package com.famlog.chat.ws;

import org.springframework.messaging.MessagingException;

import com.famlog.common.error.ErrorCode;

import lombok.Getter;

/** STOMP CONNECT·SUBSCRIBE·SEND 거부. ERROR 프레임(message 헤더 = code)을 보내고 연결을 끊는다. */
@Getter
public class StompDeniedException extends MessagingException {

    private final ErrorCode errorCode;

    public StompDeniedException(ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }
}
