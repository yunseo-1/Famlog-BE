package com.famlog.chat.ws;

import java.nio.charset.StandardCharsets;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP ERROR 프레임을 REST 에러와 같은 모양으로 만든다: message 헤더 = code, 본문 = {code, message}.
 * 앱은 AUTH_TOKEN_EXPIRED 면 토큰을 재발급해 다시 연결하고, 그 밖에는 그룹 목록을 새로 고친다.
 * 예외 메시지(내부 정보)는 내보내지 않는다.
 */
@RequiredArgsConstructor
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    private final JsonMapper jsonMapper;

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        return errorFrame(jsonMapper, denied(ex), null);
    }

    /** ERROR 프레임. sessionId 를 주면 clientOutboundChannel 로 그 연결에 보낼 수 있다 (보낸 뒤 연결이 끊긴다) */
    static Message<byte[]> errorFrame(JsonMapper jsonMapper, ErrorCode code, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code.name());
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        accessor.setLeaveMutable(true);
        byte[] body = jsonMapper.writeValueAsString(ErrorResponse.of(code, null))
                .getBytes(StandardCharsets.UTF_8);
        return MessageBuilder.createMessage(body, accessor.getMessageHeaders());
    }

    private static ErrorCode denied(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof StompDeniedException denied) {
                return denied.getErrorCode();
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
