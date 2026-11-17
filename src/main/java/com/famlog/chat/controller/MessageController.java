package com.famlog.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.chat.dto.ChatDtos.ReactionRequest;
import com.famlog.chat.dto.ChatDtos.ReactionsResponse;
import com.famlog.chat.service.ChatService;
import com.famlog.common.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 메시지 단건 동작 (사양서 11.3 CHAT-06). 다른 그룹·입장 전 메시지는 404 (존재 여부 숨김). */
@RestController
@RequestMapping("/api/v1/messages/{messageId}")
@RequiredArgsConstructor
public class MessageController {

    private final ChatService chatService;

    @Operation(summary = "모두에게서 삭제",
            description = "보낸 사람만, 보낸 지 5분 안에. 아니면 403 MESSAGE_NOT_DELETABLE. 그룹에 message.deleted 이벤트")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthUser user, @PathVariable long messageId) {
        chatService.delete(messageId, user.userId());
    }

    @Operation(summary = "공감 달기·바꾸기", description = "한 사람당 하나. 그룹에 reaction.updated 이벤트")
    @PostMapping("/reactions")
    ReactionsResponse react(AuthUser user, @PathVariable long messageId,
            @Valid @RequestBody ReactionRequest request) {
        return chatService.react(messageId, user.userId(), request.emoji());
    }

    @Operation(summary = "내 공감 취소", description = "그룹에 reaction.updated 이벤트")
    @DeleteMapping("/reactions/me")
    ReactionsResponse unreact(AuthUser user, @PathVariable long messageId) {
        return chatService.unreact(messageId, user.userId());
    }
}
