package com.famlog.candidate.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.famlog.candidate.repository.AiSourceReader;
import com.famlog.chat.event.MessagePosted;
import com.famlog.group.event.AiAnalysisEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅·그룹 이벤트로 AI 트리거를 움직인다.
 * <ul>
 * <li>새 메시지(커밋 후) → 분석 시각 계산. 실패해도 채팅에는 영향이 없다 (사양서 7.2 장애 격리)</li>
 * <li>그룹 AI 분석을 켬(같은 트랜잭션) → 기준점을 지금 메시지로 옮겨 켜기 전 대화는 보내지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AiEventListener {

    private final AiTriggerService triggerService;
    private final AiSourceReader sourceReader;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessagePosted(MessagePosted event) {
        try {
            triggerService.onMessagePosted(event.groupId(), event.messageId());
        } catch (RuntimeException e) {
            log.warn("AI 분석 트리거를 갱신하지 못했습니다: groupId={}, error={}", event.groupId(),
                    e.getClass().getSimpleName());
        }
    }

    @EventListener
    void onAiAnalysisEnabled(AiAnalysisEnabled event) {
        sourceReader.skipToLatest(event.groupId());
    }
}
