package com.famlog.candidate.domain;

public enum CandidateStatus {
    PENDING,
    APPROVED,
    DISMISSED,
    EXPIRED,
    /** 승인 후 5분 안에 되돌렸다 */
    UNDONE
}
