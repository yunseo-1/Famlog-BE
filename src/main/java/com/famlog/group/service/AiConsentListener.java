package com.famlog.group.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.user.event.AiConsentWithdrawn;

import lombok.RequiredArgsConstructor;

/** AI 분석 동의 철회 → 내가 속한 모든 그룹에서 "내 메시지 분석 제외"를 켠다 (사양서 8.3, 같은 트랜잭션) */
@Component
@RequiredArgsConstructor
class AiConsentListener {

    private final GroupMemberRepository memberRepository;
    private final Clock clock;

    @EventListener
    void onWithdrawn(AiConsentWithdrawn event) {
        Instant now = Instant.now(clock);
        memberRepository.findByUserIdAndLeftAtIsNull(event.userId()).forEach(m -> m.changeAiOptOut(true, now));
    }
}
