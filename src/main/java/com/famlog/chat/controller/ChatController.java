package com.famlog.chat.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.chat.dto.ChatDtos.MessagePage;
import com.famlog.chat.dto.ChatDtos.ReadRequest;
import com.famlog.chat.dto.ChatDtos.ReadResponse;
import com.famlog.chat.service.ChatService;
import com.famlog.chat.service.MessageQueryService;
import com.famlog.common.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 그룹 채팅방 (사양서 11.3 CHAT-03·04). 메시지 전송은 STOMP(/pub/groups/{groupId}/messages)로만 한다.
 * /groups/{groupId}/** 는 GroupPathAccessFilter가 구성원 여부를 먼저 확인한다.
 */
@RestController
@RequestMapping("/api/v1/groups/{groupId}")
@RequiredArgsConstructor
public class ChatController {

    private final MessageQueryService queryService;
    private final ChatService chatService;

    @Operation(summary = "메시지 내역",
            description = "afterSeq: 그 다음부터 오래된 순(재접속 동기화) / beforeSeq: 그 이전 최신 size개(위로 스크롤) / "
                    + "둘 다 없으면 최신 size개. items는 언제나 seq 오름차순. 들어오기 전 메시지는 보이지 않는다")
    @GetMapping("/messages")
    MessagePage messages(AuthUser user, @PathVariable long groupId,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Long beforeSeq,
            @Parameter(description = "기본 50, 최대 100") @RequestParam(required = false) Integer size) {
        return queryService.page(groupId, user.userId(), afterSeq, beforeSeq, size);
    }

    @Operation(summary = "읽음 위치 저장",
            description = "앞으로만 움직인다. 바뀌면 그룹에 read.updated 이벤트. STOMP /pub/groups/{groupId}/read 와 같다")
    @PutMapping("/read")
    ReadResponse read(AuthUser user, @PathVariable long groupId, @Valid @RequestBody ReadRequest request) {
        return chatService.markRead(groupId, user.userId(), request.lastReadSeq());
    }
}
