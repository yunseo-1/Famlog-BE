package com.famlog.auth.social;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;

@Component
public class DelegatingSocialLoginVerifier implements SocialLoginVerifier {

    private final Map<SocialProvider, SocialTokenVerifier> verifiers = new EnumMap<>(SocialProvider.class);

    public DelegatingSocialLoginVerifier(List<SocialTokenVerifier> verifiers) {
        verifiers.forEach(verifier -> this.verifiers.put(verifier.provider(), verifier));
    }

    @Override
    public SocialIdentity verify(SocialProvider provider, String providerToken) {
        SocialTokenVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 로그인 방식이에요.");
        }
        return verifier.verify(providerToken);
    }
}
