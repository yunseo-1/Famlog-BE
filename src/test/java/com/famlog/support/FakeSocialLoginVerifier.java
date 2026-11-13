package com.famlog.support;

import com.famlog.auth.social.SocialIdentity;
import com.famlog.auth.social.SocialLoginVerifier;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;

/**
 * 테스트 더블: 카카오·Google 서버 없이 제공자 토큰을 흉내 낸다.
 * <ul>
 * <li>{@code ok:<회원번호>} → 그 회원 번호로 확인됨</li>
 * <li>{@code down} → 제공자 장애 (INTERNAL_ERROR)</li>
 * <li>그 밖 → 잘못된 토큰 (AUTH_INVALID)</li>
 * </ul>
 * APPLE은 실제 구현처럼 지원하지 않는다. 실제 카카오·Google 검증기는 각자의 단위 테스트에서 확인한다.
 */
public class FakeSocialLoginVerifier implements SocialLoginVerifier {

    public static String token(String providerUserId) {
        return "ok:" + providerUserId;
    }

    @Override
    public SocialIdentity verify(SocialProvider provider, String providerToken) {
        if (provider == SocialProvider.APPLE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 로그인 방식이에요.");
        }
        if ("down".equals(providerToken)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (providerToken == null || !providerToken.startsWith("ok:")) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        return new SocialIdentity(provider, providerToken.substring(3));
    }
}
