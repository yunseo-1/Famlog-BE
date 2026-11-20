package com.famlog.media.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.domain.Message;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.flag.FeatureFlag;
import com.famlog.common.flag.FeatureFlags;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.media.domain.MediaAsset;
import com.famlog.media.domain.MediaStatus;
import com.famlog.media.domain.MediaType;
import com.famlog.media.domain.MediaVariant;
import com.famlog.media.dto.MediaDtos.MediaResponse;
import com.famlog.media.dto.MediaDtos.UploadItem;
import com.famlog.media.dto.MediaDtos.UploadTarget;
import com.famlog.media.dto.MediaDtos.UploadUrlsResponse;
import com.famlog.media.repository.MediaAssetRepository;
import com.famlog.media.storage.MediaStorage;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.repository.ConsentRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사진·동영상 업로드 (사양서 4.2 CHAT-05, 9.1).
 * <ol>
 * <li>upload-urls: 개수·형식·크기를 검사하고 10분짜리 PUT URL (객체 키는 UUID)</li>
 * <li>앱이 S3에 직접 PUT</li>
 * <li>complete: 실제로 올라왔는지, 파일 앞부분이 신고한 형식인지 확인 → 워커에 처리 요청 (위장 파일은 REJECTED)</li>
 * <li>워커가 GPS를 지운 원본·썸네일을 만들면 READY (MediaResultHandler)</li>
 * </ol>
 * 다른 그룹의 미디어는 404로 숨긴다 (사양서 9.3).
 */
@Service
@RequiredArgsConstructor
public class MediaService {

    /** complete 를 다시 불렀을 때 처리 요청을 다시 보내는 기준 (요청이 유실됐을 수 있다) */
    static final Duration REPUBLISH_AFTER = Duration.ofMinutes(2);

    private static final TypeReference<Map<String, String>> VARIANTS_TYPE = new TypeReference<>() {
    };

    private final MediaAssetRepository mediaRepository;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository memberRepository;
    private final ConsentRepository consentRepository;
    private final GroupAccessChecker accessChecker;
    private final MediaStorage storage;
    private final MediaJobPublisher jobPublisher;
    private final MediaProperties properties;
    private final FeatureFlags featureFlags;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public UploadUrlsResponse issueUploadUrls(long userId, long groupId, List<UploadItem> items) {
        accessChecker.requireMember(groupId, userId);
        if (items.size() > properties.maxItems()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "한 번에 " + properties.maxItems() + "개까지 보낼 수 있어요.");
        }
        Instant expiresAt = Instant.now(clock).plus(properties.uploadUrlTtl());
        List<UploadTarget> targets = new ArrayList<>();
        for (UploadItem item : items) {
            String mime = MediaFormats.normalize(item.mimeType());
            MediaType type = MediaFormats.typeOf(mime)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "보낼 수 없는 파일 형식이에요."));
            if (item.size() > maxSize(type)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, type == MediaType.IMAGE
                        ? "사진이 너무 커요." : "동영상은 500MB까지 보낼 수 있어요.");
            }
            String key = "media/" + UUID.randomUUID() + "/original";
            MediaAsset media = mediaRepository.save(
                    new MediaAsset(groupId, userId, type, mime, item.size(), key, expiresAt));
            URI url = storage.presignPut(key, mime, item.size(), properties.uploadUrlTtl());
            targets.add(new UploadTarget(media.getId(), url.toString(), "PUT", Map.of("Content-Type", mime),
                    expiresAt));
        }
        return new UploadUrlsResponse(targets);
    }

    /**
     * 업로드 확인. 이미 확인한 미디어는 그대로 돌려준다 (재시도 안전).
     * 형식이 다르면 REJECTED 로 남기고 파일을 지운 뒤 400 (REJECTED 상태는 커밋한다).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public MediaResponse complete(long mediaId, long userId) {
        MediaAsset media = mediaRepository.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.requireMemberOrNotFound(media.getGroupId(), userId);
        if (media.getUploaderId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        switch (media.getStatus()) {
            case REJECTED -> throw rejected();
            case READY, FAILED -> {
                return MediaResponse.of(media);
            }
            case UPLOADED -> {
                if (media.getUpdatedAt().isBefore(Instant.now(clock).minus(REPUBLISH_AFTER))) {
                    jobPublisher.publish(media, extractLocation(userId));
                }
                return MediaResponse.of(media);
            }
            case PENDING_UPLOAD -> {
                // 아래에서 확인
            }
        }

        long size = storage.size(media.getStorageKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "파일이 아직 올라가지 않았어요."));
        Optional<String> detected = size > maxSize(media.getMediaType()) ? Optional.empty()
                : MagicBytes.detect(storage.readHead(media.getStorageKey(), MagicBytes.HEAD_LENGTH));
        boolean valid = detected.isPresent() && MagicBytes.matches(media.getMime(), detected.get())
                && MediaFormats.typeOf(detected.get()).orElse(null) == media.getMediaType();
        if (!valid) {
            media.reject(detected.isEmpty() && size > maxSize(media.getMediaType()) ? "TOO_LARGE" : "TYPE_MISMATCH");
            storage.delete(media.getStorageKey());
            throw rejected();
        }
        media.markUploaded(detected.get(), size);
        jobPublisher.publish(media, extractLocation(userId));
        return MediaResponse.of(media);
    }

    /**
     * 내려받기 URL (10분). 볼 수 있는 미디어만: 내 그룹이고, 보낸 메시지가 지워지지 않았고 내가 들어온 뒤의 메시지
     * (아직 보내지 않은 미디어는 올린 사람만). 처리가 끝나기 전(GPS 제거 전)에는 주지 않는다.
     */
    @Transactional(readOnly = true)
    public URI downloadUrl(long mediaId, long userId, MediaVariant variant) {
        MediaAsset media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.requireMemberOrNotFound(media.getGroupId(), userId);
        if (!isVisible(media, userId) || media.getStatus() != MediaStatus.READY) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String key = variant == MediaVariant.ORIGINAL ? media.getStorageKey() : variantKey(media, variant);
        return storage.presignGet(key, properties.downloadUrlTtl());
    }

    /** 신고할 수 있는 미디어의 그룹 (MOD-01): 내가 볼 수 있는 메시지에 붙은 미디어. 아니면 404 */
    @Transactional(readOnly = true)
    public long groupOfReportableMedia(long mediaId, long userId) {
        MediaAsset media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.requireMemberOrNotFound(media.getGroupId(), userId);
        if (!isVisible(media, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return media.getGroupId();
    }

    /** 워커가 위치를 읽어도 되는지: 기능 플래그가 켜져 있고 올린 사람이 "사진 위치 사용"(LOCATION)에 동의 (사양서 8.6) */
    public boolean extractLocation(long userId) {
        return featureFlags.enabled(FeatureFlag.MEDIA_PHOTO_LOCATION, properties.photoLocationEnabled())
                && consentRepository.existsByUserIdAndTypeAndWithdrawnAtIsNull(userId, ConsentType.LOCATION);
    }

    private boolean isVisible(MediaAsset media, long userId) {
        if (media.getMessageId() == null) {
            return media.getUploaderId() == userId;
        }
        Message message = messageRepository.findById(media.getMessageId()).orElse(null);
        if (message == null || message.isDeleted()) {
            return false;
        }
        long joinSeq = memberRepository.findByGroupIdAndUserId(media.getGroupId(), userId)
                .map(member -> member.getJoinSeq()).orElse(Long.MAX_VALUE);
        return message.getSeq() > joinSeq;
    }

    private String variantKey(MediaAsset media, MediaVariant variant) {
        if (media.getVariants() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String key = jsonMapper.readValue(media.getVariants(), VARIANTS_TYPE).get(variant.name());
        if (key == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return key;
    }

    private long maxSize(MediaType type) {
        return (type == MediaType.IMAGE ? properties.maxImageSize() : properties.maxVideoSize()).toBytes();
    }

    private static BusinessException rejected() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "보낼 수 없는 파일이에요. 사진·동영상인지 확인해 주세요.");
    }
}
