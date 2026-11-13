package com.famlog.auth.jwt;

/** Access JWT 클레임. 권한(그룹 역할)은 토큰에 넣지 않고 매 요청 DB에서 확인한다 (사양서 9.2). */
public final class FamlogJwtClaims {

    /** 세션 ID (user_sessions.id). 원격 로그아웃 시 즉시 무효화에 쓴다 */
    public static final String SESSION_ID = "sid";

    private FamlogJwtClaims() {
    }
}
