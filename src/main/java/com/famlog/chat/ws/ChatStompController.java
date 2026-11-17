package com.famlog.chat.ws;

import java.security.Principal;
import java.util.Set;

import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.dto.ChatDtos.ErrorEvent;
import com.famlog.chat.dto.ChatDtos.ReadRequest;
import com.famlog.chat.dto.ChatDtos.SendMessageRequest;
import com.famlog.chat.service.ChatService;
import com.famlog.chat.service.ChatService.SendResult;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP 메시지 처리 (사양서 11.4). 구성원 여부는 StompAuthInterceptor가 이미 확인했다.
 * 실패는 보낸 연결에만 /user/queue/events 로 {type: "error", data: {code, message, clientMessageId}} 를 보낸다.
 * 성공한 새 메시지는 그룹 토픽으로 가고, 재전송(이미 저장됨)이면 보낸 연결에만 message.created 를 다시 보낸다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;
    private final Validator validator;

    @MessageMapping("/groups/{groupId}/messages")
    @SendToUser(destinations = "/queue/events", broadcast = false)
    public ChatEvent send(@DestinationVariable long groupId, @Payload SendMessageRequest request,
            Principal principal) {
        String clientMessageId = request.clientMessageId();
        if (!isValid(request)) {
            return error(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getMessage(), clientMessageId);
        }
        try {
            SendResult result = chatService.send(groupId, userId(principal), request);
            return result.duplicate() ? new ChatEvent(ChatEvent.MESSAGE_CREATED, result.message()) : null;
        } catch (BusinessException e) {
            return error(e.getErrorCode(), e.getMessage(), clientMessageId);
        }
    }

    @MessageMapping("/groups/{groupId}/read")
    @SendToUser(destinations = "/queue/events", broadcast = false)
    public ChatEvent read(@DestinationVariable long groupId, @Payload ReadRequest request, Principal principal) {
        if (!isValid(request)) {
            return error(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getMessage(), null);
        }
        try {
            chatService.markRead(groupId, userId(principal), request.lastReadSeq());
            return null;
        } catch (BusinessException e) {
            return error(e.getErrorCode(), e.getMessage(), null);
        }
    }

    /** 본문이 JSON이 아니거나 형식이 틀린 경우 등 */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/events", broadcast = false)
    public ChatEvent handle(Exception e) {
        if (e instanceof BusinessException business) {
            return error(business.getErrorCode(), business.getMessage(), null);
        }
        log.warn("STOMP 메시지 처리 실패: error={}", e.getClass().getSimpleName());
        boolean badPayload = e instanceof MessageConversionException || e instanceof MethodArgumentNotValidException;
        ErrorCode code = badPayload ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        return error(code, code.getMessage(), null);
    }

    private <T> boolean isValid(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        return violations.isEmpty();
    }

    private static long userId(Principal principal) {
        return ((StompPrincipal) principal).userId();
    }

    private static ChatEvent error(ErrorCode code, String message, String clientMessageId) {
        return new ChatEvent(ChatEvent.ERROR, new ErrorEvent(code.name(), message, clientMessageId));
    }
}
