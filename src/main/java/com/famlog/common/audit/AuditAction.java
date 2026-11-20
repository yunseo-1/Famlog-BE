package com.famlog.common.audit;

/** 감사 로그 동작 (사양서 9.8). 계정 삭제·데이터 내보내기는 S20-3·4에서 추가한다. ADMIN_* 은 관리자 도구 (actor 없음) */
public enum AuditAction {
    GROUP_ROLE_CHANGED,
    GROUP_OWNER_TRANSFERRED,
    GROUP_MEMBER_KICKED,
    GROUP_DELETION_SCHEDULED,
    ADMIN_REPORT_VIEWED,
    ADMIN_REPORT_RESOLVED,
    ADMIN_HOLIDAY_ADDED,
    ADMIN_HOLIDAY_DELETED,
    ADMIN_APP_VERSION_CHANGED,
    ADMIN_FEATURE_FLAG_CHANGED
}
