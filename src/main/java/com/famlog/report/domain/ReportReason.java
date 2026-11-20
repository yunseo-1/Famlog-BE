package com.famlog.report.domain;

/** 신고 사유 (SCR-82). OTHER 는 상세가 필요하다. CHILD_SAFETY 는 관리자 목록 맨 위 (Play 아동 안전 기준) */
public enum ReportReason {
    SPAM,
    HARASSMENT,
    SEXUAL,
    VIOLENCE,
    CHILD_SAFETY,
    PRIVACY,
    AI_INACCURATE,
    OTHER
}
