package com.famlog.user.domain;

public enum UserStatus {
    ACTIVE,
    /** 계정 삭제 요청 후 30일 유예 중 (ACC-01) */
    PENDING_DELETION,
    DELETED
}
