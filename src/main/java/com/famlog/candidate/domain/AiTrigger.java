package com.famlog.candidate.domain;

/** 분석 요청을 만든 이유 (사양서 4.5 ARC-01) */
public enum AiTrigger {
    /** 규칙 필터에 걸린 메시지 + 대화 30초 멈춤 */
    RULE,
    /** 보조 분석: 분석 안 된 메시지 30개 또는 10분 무응답 */
    BATCH
}
