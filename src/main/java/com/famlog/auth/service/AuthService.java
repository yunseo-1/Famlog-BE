package com.famlog.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.auth.social.SocialIdentity;
import com.famlog.auth.social.SocialLoginVerifier;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.Consent;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.domain.SocialAccount;
import com.famlog.user.domain.SocialProvider;
import com.famlog.user.domain.User;
import com.famlog.user.repository.ConsentRepository;
import com.famlog.user.repository.SocialAccountRepository;
import com.famlog.user.repository.UserRepository;
import com.famlog.user.service.AgePolicy;
import com.famlog.user.service.ConsentProperties;

import lombok.RequiredArgsConstructor;

/**
 * 소셜 로그인·가입 (사양서 4.1 AUTH-01·02).
 * <ol>
 * <li>로그인: 가입된 사용자면 토큰 발급, 처음 보는 사용자면 아무것도 저장하지 않고 USER_NOT_REGISTERED</li>
 * <li>가입: 약관 동의 + 닉네임 + 출생연도월. 만 14세 미만이면 AGE_RESTRICTED, 역시 아무것도 저장하지 않는다</li>
 * </ol>
 * 제공자 토큰은 항상 먼저 검증한다 (검증 전에는 가입 여부도 알려주지 않는다).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SocialLoginVerifier socialLoginVerifier;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final ConsentRepository consentRepository;
    private final SessionTokenService sessionTokenService;
    private final AgePolicy agePolicy;
    private final ConsentProperties consentProperties;
    private final Clock clock;

    @Transactional
    public AuthResult login(SocialProvider provider, String providerToken, DeviceInfo device) {
        SocialIdentity identity = socialLoginVerifier.verify(provider, providerToken);
        SocialAccount account = socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_REGISTERED));
        User user = userRepository.findById(account.getUserId()).orElseThrow();
        if (!user.isActive()) {
            // 삭제 유예 중 복구는 ACC-01(S20)에서 만든다
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        return new AuthResult(startSession(user, device), user);
    }

    @Transactional
    public AuthResult signup(SocialProvider provider, String providerToken, DeviceInfo device,
            SignupCommand command) {
        SocialIdentity identity = socialLoginVerifier.verify(provider, providerToken);
        if (socialAccountRepository.existsByProviderAndProviderUserId(identity.provider(),
                identity.providerUserId())) {
            throw alreadyRegistered();
        }
        requireCurrentConsents(command);
        YearMonth birth = agePolicy.parse(command.birthYm())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "출생연도와 월을 확인해 주세요."));
        if (!agePolicy.isOldEnough(birth)) {
            throw new BusinessException(ErrorCode.AGE_RESTRICTED);
        }

        Instant now = Instant.now(clock);
        User user = userRepository.save(User.signUp(command.nickname().strip(), command.birthYm(), now));
        try {
            socialAccountRepository.saveAndFlush(
                    new SocialAccount(user.getId(), identity.provider(), identity.providerUserId()));
        } catch (DataIntegrityViolationException e) {
            // 같은 계정으로 가입 요청이 동시에 두 번 온 경우
            throw alreadyRegistered();
        }
        consentRepository.save(new Consent(user.getId(), ConsentType.TERMS, command.termsVersion(), now));
        consentRepository.save(new Consent(user.getId(), ConsentType.PRIVACY, command.privacyVersion(), now));
        return new AuthResult(startSession(user, device), user);
    }

    private TokenPair startSession(User user, DeviceInfo device) {
        sessionTokenService.revokeDeviceSessions(user.getId(), device.deviceId());
        return sessionTokenService.startSession(user.getId(), device.deviceId(), device.deviceName(),
                device.platform());
    }

    /** 앱이 보여준 문서 버전이 현재 버전과 같아야 한다. 다르면 앱이 최신 약관을 다시 보여줘야 한다 */
    private void requireCurrentConsents(SignupCommand command) {
        if (!consentProperties.termsVersion().equals(command.termsVersion())
                || !consentProperties.privacyVersion().equals(command.privacyVersion())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "약관이 바뀌었어요. 최신 약관을 확인해 주세요.");
        }
    }

    private static BusinessException alreadyRegistered() {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, "이미 가입된 계정이에요. 로그인해 주세요.");
    }
}
