package com.famlog.group.domain;

public enum GroupStatus {
    ACTIVE,
    /** 삭제 예정 (30일 유예). 이때부터 구성원도 접근할 수 없다 */
    PENDING_DELETION
}
