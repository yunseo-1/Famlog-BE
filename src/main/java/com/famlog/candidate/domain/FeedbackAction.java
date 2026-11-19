package com.famlog.candidate.domain;

/** 후보 처리 기록 (사양서 7.3 지표) */
public enum FeedbackAction {
    /** 고치지 않고 등록 */
    APPROVED,
    /** 고쳐서 등록 */
    EDITED,
    DISMISSED,
    UNDONE
}
