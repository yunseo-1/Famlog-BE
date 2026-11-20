package com.famlog.report.domain;

/** 관리자 조치. 메시지·사진은 모두에게서 숨기고(삭제 처리), AI 후보는 대기 중이면 무시로 바꾼다. 사용자 신고는 기록만 (정지는 v1.1) */
public enum ReportAction {
    HIDE_MESSAGE,
    DISMISS_CANDIDATE
}
