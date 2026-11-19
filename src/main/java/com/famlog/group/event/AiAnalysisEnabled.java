package com.famlog.group.event;

/**
 * 그룹 AI 분석이 꺼져 있다가 켜졌다 (SET-01). 같은 트랜잭션 안에서 AI 트리거가 분석 기준점을 지금 메시지로 옮긴다
 * (켜기 전 대화는 보내지 않는다).
 */
public record AiAnalysisEnabled(long groupId) {
}
