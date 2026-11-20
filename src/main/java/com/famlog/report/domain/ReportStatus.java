package com.famlog.report.domain;

/** OPEN(접수) → REVIEWED(확인, 조치 없음 판단 전) / ACTIONED(조치함) / REJECTED(문제 없음) */
public enum ReportStatus {
    OPEN,
    REVIEWED,
    ACTIONED,
    REJECTED
}
