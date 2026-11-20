package com.famlog.report.domain;

/** 신고 대상 (사양서 10.2 reports.target_type). AI_OUTPUT 은 등록 후보 */
public enum ReportTargetType {
    MESSAGE,
    MEDIA,
    AI_OUTPUT,
    USER
}
