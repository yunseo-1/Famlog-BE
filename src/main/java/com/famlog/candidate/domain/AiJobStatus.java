package com.famlog.candidate.domain;

public enum AiJobStatus {
    /** 워커에 보냈다 */
    QUEUED,
    DONE,
    /** 워커가 3번 실패했거나 LLM 출력이 스키마에 맞지 않았다 */
    FAILED
}
