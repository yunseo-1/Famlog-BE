package com.famlog.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.dto.ConsentDtos.ConsentListResponse;
import com.famlog.user.dto.ConsentDtos.ConsentResponse;
import com.famlog.user.dto.ConsentDtos.UpdateConsentRequest;
import com.famlog.user.service.ConsentService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 내 동의 (사양서 8.3, 11.3). */
@RestController
@RequestMapping("/api/v1/users/me/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    @Operation(summary = "내 동의 상태", description = "종류 5개 모두. reconsentRequired=true 면 문서가 바뀌어 다시 받아야 한다")
    @GetMapping
    ConsentListResponse listConsents(AuthUser user) {
        return consentService.list(user.userId());
    }

    @Operation(summary = "동의·철회", description = "지금은 LOCATION(위치기반서비스 이용약관 + 개인위치정보 수집·이용)만. "
            + "동의 {agreed:true, docVersion}(현재 버전과 다르면 400), 철회 {agreed:false} → 내가 올린 사진의 위치도 지운다. "
            + "TERMS·PRIVACY·AI_ANALYSIS·OVERSEAS_TRANSFER 는 400")
    @PutMapping("/{type}")
    ConsentResponse updateConsent(AuthUser user, @PathVariable ConsentType type,
            @Valid @RequestBody UpdateConsentRequest request) {
        return consentService.update(user.userId(), type, request);
    }
}
