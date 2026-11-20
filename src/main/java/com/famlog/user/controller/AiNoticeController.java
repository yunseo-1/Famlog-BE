package com.famlog.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.user.service.ConsentProperties;
import com.famlog.user.service.ConsentProperties.AiNotice;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/** AI 분석 안내 (사양서 4.1 SET-01, 8.3 국외 이전 고지, 8.7). 문구는 서버 설정이라 앱 업데이트 없이 고칠 수 있다 */
@RestController
@RequiredArgsConstructor
public class AiNoticeController {

    private final ConsentProperties properties;

    @Operation(summary = "AI 분석 안내", description = "무엇이 어디로 전송되는지와 국외 이전 고지. 그룹 AI 분석을 켤 때(관리자)와 "
            + "그룹 설정의 aiNoticePending=true 일 때(구성원) 보여 주고, 확인한 version 을 보낸다")
    @GetMapping("/api/v1/ai-notice")
    AiNotice aiNotice() {
        return properties.aiNotice();
    }
}
