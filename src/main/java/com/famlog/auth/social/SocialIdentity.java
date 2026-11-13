package com.famlog.auth.social;

import com.famlog.user.domain.SocialProvider;

/** 제공자가 확인해 준 회원 번호. 이메일·이름 같은 프로필은 받지도 저장하지도 않는다 (사양서 8.2). */
public record SocialIdentity(SocialProvider provider, String providerUserId) {
}
