package com.famlog.group.domain;

/** 그룹 권한 역할 (사양서 2.2). 선언 순서가 권한 크기: MEMBER < ADMIN < OWNER. */
public enum GroupRole {
    MEMBER,
    ADMIN,
    OWNER;

    public boolean atLeast(GroupRole required) {
        return compareTo(required) >= 0;
    }
}
