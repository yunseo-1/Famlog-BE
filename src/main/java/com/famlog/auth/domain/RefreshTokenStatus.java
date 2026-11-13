package com.famlog.auth.domain;

public enum RefreshTokenStatus {
    /** 지금 쓸 수 있는 토큰 (세션당 하나) */
    ACTIVE,
    /** 재발급으로 교체된 토큰. 다시 들어오면 재사용(탈취)으로 본다 */
    ROTATED,
    /** 로그아웃·원격 로그아웃·전체 폐기로 무효가 된 토큰 */
    REVOKED
}
