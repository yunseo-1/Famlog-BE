package com.famlog.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.media.repository.MediaAssetRepository;
import com.famlog.user.domain.Consent;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.dto.ConsentDtos.ConsentListResponse;
import com.famlog.user.dto.ConsentDtos.ConsentResponse;
import com.famlog.user.dto.ConsentDtos.UpdateConsentRequest;
import com.famlog.user.repository.ConsentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 동의 상태·변경 (사양서 8.3, 11.3). 이력은 지우지 않는다: 철회는 withdrawnAt 을 채우고, 새 버전 동의는 새 행을 남긴다.
 * <ul>
 * <li>지금 바꿀 수 있는 것은 위치기반서비스(LOCATION)만. 필수 동의(약관·개인정보)는 탈퇴로만 철회하고,
 * AI 분석·국외 이전은 S16(SET-01)에서 연다</li>
 * <li>위치 동의를 철회하면 내가 올린 사진의 위치를 지운다 (8.3 "설정에서 끄기 → 저장된 사진 위치 삭제")</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final MediaAssetRepository mediaRepository;
    private final ConsentProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ConsentListResponse list(long userId) {
        Map<ConsentType, Consent> current = consentRepository.findByUserIdAndWithdrawnAtIsNull(userId).stream()
                .collect(Collectors.toMap(Consent::getType, consent -> consent, latest()));
        return new ConsentListResponse(Arrays.stream(ConsentType.values())
                .map(type -> responseOf(type, Optional.ofNullable(current.get(type))))
                .toList());
    }

    @Transactional
    public ConsentResponse update(long userId, ConsentType type, UpdateConsentRequest request) {
        if (type != ConsentType.LOCATION) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, type == ConsentType.TERMS
                    || type == ConsentType.PRIVACY ? "필수 동의는 탈퇴로만 철회할 수 있어요." : "아직 여기서 바꿀 수 없는 동의예요.");
        }
        List<Consent> active = consentRepository.findByUserIdAndTypeAndWithdrawnAtIsNull(userId, type);
        Instant now = Instant.now(clock);
        if (!request.agreed()) {
            active.forEach(consent -> consent.withdraw(now));
            mediaRepository.clearLocations(userId);
            return responseOf(type, Optional.empty());
        }
        String version = properties.currentVersion(type);
        if (!version.equals(request.docVersion())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "약관이 바뀌었어요. 최신 약관을 확인해 주세요.");
        }
        Optional<Consent> same = active.stream().filter(consent -> version.equals(consent.getDocVersion())).findFirst();
        if (same.isPresent()) {
            return responseOf(type, same);
        }
        // 예전 버전 동의는 새 버전 동의로 바뀐다
        active.forEach(consent -> consent.withdraw(now));
        return responseOf(type, Optional.of(consentRepository.save(new Consent(userId, type, version, now))));
    }

    private ConsentResponse responseOf(ConsentType type, Optional<Consent> consent) {
        String currentVersion = properties.currentVersion(type);
        return consent
                .map(found -> new ConsentResponse(type, true, found.getDocVersion(), found.getAgreedAt(),
                        currentVersion, currentVersion != null && !currentVersion.equals(found.getDocVersion())))
                .orElseGet(() -> new ConsentResponse(type, false, null, null, currentVersion, false));
    }

    private static BinaryOperator<Consent> latest() {
        return BinaryOperator.maxBy(Comparator.comparing(Consent::getAgreedAt));
    }
}
