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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponse;
import com.famlog.media.repository.MediaAssetRepository;
import com.famlog.user.domain.Consent;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.dto.ConsentDtos.ConsentListResponse;
import com.famlog.user.dto.ConsentDtos.ConsentResponse;
import com.famlog.user.dto.ConsentDtos.UpdateConsentRequest;
import com.famlog.user.event.AiConsentWithdrawn;
import com.famlog.user.repository.ConsentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 동의 상태·변경 (사양서 8.3, 11.3). 이력은 지우지 않는다: 철회는 withdrawnAt 을 채우고, 새 버전 동의는 새 행을 남긴다.
 * <ul>
 * <li>바꿀 수 있는 것은 위치기반서비스(LOCATION)와 AI 분석(AI_ANALYSIS). 필수 동의(약관·개인정보)는 탈퇴로만 철회한다</li>
 * <li>위치 동의를 철회하면 내가 올린 사진의 위치를 지운다 (8.3 "설정에서 끄기 → 저장된 사진 위치 삭제")</li>
 * <li>AI 분석은 안내 확인(고지) 기록이다: 확인하면 AI_ANALYSIS·OVERSEAS_TRANSFER 를 같은 안내 버전으로 함께 남긴다.
 * 철회하면 둘 다 철회하고 내 모든 그룹에서 "내 메시지 분석 제외"를 켠다 ({@link AiConsentWithdrawn}).
 * 다시 확인해도 그룹별 제외는 자동으로 풀지 않는다. OVERSEAS_TRANSFER 만 따로 바꿀 수는 없다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final MediaAssetRepository mediaRepository;
    private final ConsentProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private static final List<ConsentType> AI_TYPES = List.of(ConsentType.AI_ANALYSIS, ConsentType.OVERSEAS_TRANSFER);

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
        if (type == ConsentType.AI_ANALYSIS) {
            return updateAi(userId, request);
        }
        if (type != ConsentType.LOCATION) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, type == ConsentType.TERMS
                    || type == ConsentType.PRIVACY ? "필수 동의는 탈퇴로만 철회할 수 있어요."
                            : "국외 이전 고지는 AI 분석 안내와 함께 바뀌어요. AI_ANALYSIS 로 보내 주세요.");
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

    /**
     * AI 분석 안내 확인 (SET-01). 관리자가 그룹 AI 분석을 켤 때와 구성원이 안내를 볼 때 부른다.
     *
     * @param version 앱이 보여 준 안내 버전 (현재 버전과 다르면 400 aiNoticeVersion)
     */
    @Transactional
    public void acknowledgeAiNotice(long userId, String version, String field) {
        String current = properties.aiNotice().version();
        if (!current.equals(version)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 분석 안내가 바뀌었어요. 최신 안내를 확인해 주세요.",
                    List.of(new ErrorResponse.FieldError(field, "현재 안내 버전 " + current + " 을 보내 주세요.")));
        }
        Instant now = Instant.now(clock);
        for (ConsentType type : AI_TYPES) {
            List<Consent> active = consentRepository.findByUserIdAndTypeAndWithdrawnAtIsNull(userId, type);
            if (active.stream().noneMatch(consent -> current.equals(consent.getDocVersion()))) {
                active.forEach(consent -> consent.withdraw(now));
                consentRepository.save(new Consent(userId, type, current, now));
            }
        }
    }

    /** 현재 버전의 AI 분석 안내를 확인했는가 (아니면 앱이 안내를 한 번 보여 준다) */
    @Transactional(readOnly = true)
    public boolean hasCurrentAiNotice(long userId) {
        String current = properties.aiNotice().version();
        return consentRepository.findByUserIdAndTypeAndWithdrawnAtIsNull(userId, ConsentType.AI_ANALYSIS).stream()
                .anyMatch(consent -> current.equals(consent.getDocVersion()));
    }

    private ConsentResponse updateAi(long userId, UpdateConsentRequest request) {
        if (request.agreed()) {
            acknowledgeAiNotice(userId, request.docVersion(), "docVersion");
        } else {
            Instant now = Instant.now(clock);
            for (ConsentType type : AI_TYPES) {
                consentRepository.findByUserIdAndTypeAndWithdrawnAtIsNull(userId, type)
                        .forEach(consent -> consent.withdraw(now));
            }
            events.publishEvent(new AiConsentWithdrawn(userId));
        }
        return responseOf(ConsentType.AI_ANALYSIS, consentRepository
                .findByUserIdAndTypeAndWithdrawnAtIsNull(userId, ConsentType.AI_ANALYSIS).stream()
                .max(Comparator.comparing(Consent::getAgreedAt)));
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
