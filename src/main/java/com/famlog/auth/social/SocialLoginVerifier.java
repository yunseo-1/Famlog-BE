package com.famlog.auth.social;

import com.famlog.user.domain.SocialProvider;

/** 제공자에 맞는 검증기로 토큰을 확인한다. 지원하지 않는 제공자(APPLE 등)는 400. */
public interface SocialLoginVerifier {

    SocialIdentity verify(SocialProvider provider, String providerToken);
}
