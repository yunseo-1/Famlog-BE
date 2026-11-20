package com.famlog.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.user.domain.AccountDeletionRequest;
import com.famlog.user.service.AccountDeletionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/** 계정 삭제 (사양서 4.1 ACC-01, 11.3 DELETE /users/me). 웹 경로는 /account-deletion 안내 페이지 */
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountDeletionService deletionService;

    /** @param deleteMedia 내가 올린 사진·동영상도 지운다 (기본 false: 가족 앨범에 남는다) */
    public record DeleteAccountRequest(@Schema(description = "내가 올린 사진·동영상도 지우기 (기본 false)") Boolean deleteMedia) {
    }

    @Operation(summary = "계정 삭제 요청", description = "즉시 모든 기기에서 로그아웃되고 30일 뒤 개인정보를 파기한다. 그동안 로그인하면 "
            + "복구할 수 있다(로그인 403 ACCOUNT_PENDING_DELETION → restore:true). 다른 구성원이 있는 그룹의 그룹장이면 "
            + "409 OWNER_TRANSFER_REQUIRED(먼저 넘기기), 혼자인 그룹은 함께 삭제 예정. 다시 요청해도 204")
    @DeleteMapping("/api/v1/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(AuthUser user, @RequestBody(required = false) DeleteAccountRequest request) {
        deletionService.request(user.userId(), request != null && Boolean.TRUE.equals(request.deleteMedia()),
                AccountDeletionRequest.SOURCE_APP);
    }
}
