package com.famlog.media.controller;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.media.domain.MediaVariant;
import com.famlog.media.dto.MediaDtos.MediaResponse;
import com.famlog.media.dto.MediaDtos.UploadUrlsRequest;
import com.famlog.media.dto.MediaDtos.UploadUrlsResponse;
import com.famlog.media.service.MediaService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 사진·동영상 (사양서 11.3 CHAT-05). 흐름: upload-urls → 앱이 S3에 PUT → complete → STOMP로 IMAGE·VIDEO 메시지 전송.
 * 썸네일은 처리가 끝나면 그룹 이벤트 media.updated 로 알린다.
 */
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @Operation(summary = "업로드 URL 발급",
            description = "한 번에 30개. 사진(jpeg·png·webp·heic) 30MB, 동영상(mp4·mov) 500MB·5분 이하. URL은 10분 유효, "
                    + "응답의 headers 를 그대로 넣어 PUT 한다")
    @PostMapping("/upload-urls")
    @ResponseStatus(HttpStatus.CREATED)
    UploadUrlsResponse uploadUrls(AuthUser user, @Valid @RequestBody UploadUrlsRequest request) {
        return mediaService.issueUploadUrls(user.userId(), request.groupId(), request.items());
    }

    @Operation(summary = "업로드 완료",
            description = "파일 앞부분으로 실제 형식을 확인한다. 형식이 다르면 400(파일은 지워진다). 다시 불러도 안전하다")
    @PostMapping("/{mediaId}/complete")
    MediaResponse complete(AuthUser user, @PathVariable long mediaId) {
        return mediaService.complete(mediaId, user.userId());
    }

    @Operation(summary = "내려받기",
            description = "10분짜리 저장소 주소로 302. 처리가 끝난(READY) 미디어만. 썸네일은 긴 변 256(S)·720(M)·1440(L)px WebP")
    @GetMapping("/{mediaId}/content")
    ResponseEntity<Void> content(AuthUser user, @PathVariable long mediaId,
            @RequestParam(defaultValue = "THUMB_M") MediaVariant variant) {
        URI url = mediaService.downloadUrl(mediaId, user.userId(), variant);
        return ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(CacheControl.noStore()).build();
    }
}
