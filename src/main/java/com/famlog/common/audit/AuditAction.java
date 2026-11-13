package com.famlog.common.audit;

/** 감사 로그 동작 (사양서 9.8). 계정 삭제·데이터 내보내기·신고 열람은 S20에서 추가한다. */
public enum AuditAction {
    GROUP_ROLE_CHANGED,
    GROUP_OWNER_TRANSFERRED,
    GROUP_MEMBER_KICKED,
    GROUP_DELETION_SCHEDULED
}
