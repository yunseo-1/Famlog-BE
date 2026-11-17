package com.famlog.media.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 미디어 설정 (application.yml famlog.media, 사양서 4.2 CHAT-05, 8.6, 9.1).
 *
 * @param maxItems             한 번에 올릴 수 있는 개수 (30)
 * @param maxImageSize         사진 한 장 최대 크기
 * @param maxVideoSize         동영상 최대 크기 (500MB)
 * @param maxVideoSeconds      동영상 최대 길이 (5분). 길이는 워커가 읽고 넘으면 REJECTED
 * @param uploadUrlTtl         업로드 URL 유효 시간 (10분)
 * @param downloadUrlTtl       내려받기 URL 유효 시간 (10분)
 * @param photoLocationEnabled 사진 위치 저장 기능 플래그. 위치기반서비스 신고 확인 전까지 꺼 둔다 (사양서 8.6)
 */
@ConfigurationProperties("famlog.media")
public record MediaProperties(
        @DefaultValue("30") int maxItems,
        @DefaultValue("30MB") DataSize maxImageSize,
        @DefaultValue("500MB") DataSize maxVideoSize,
        @DefaultValue("300") int maxVideoSeconds,
        @DefaultValue("10m") Duration uploadUrlTtl,
        @DefaultValue("10m") Duration downloadUrlTtl,
        @DefaultValue("false") boolean photoLocationEnabled,
        @DefaultValue S3 s3,
        @DefaultValue Streams streams) {

    /**
     * @param endpoint       서버가 쓰는 S3 주소. 비우면 AWS 기본 (운영)
     * @param publicEndpoint 앱에 주는 Presigned URL 주소. 비우면 endpoint (로컬 에뮬레이터: http://10.0.2.2:8333)
     */
    public record S3(String endpoint, String publicEndpoint, @DefaultValue("ap-northeast-2") String region,
            String accessKey, String secretKey, @DefaultValue("famlog-media") String bucket) {

        public String presignEndpoint() {
            return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        }
    }

    /**
     * Redis Streams 이름 (워커 worker/app/config.py 와 같아야 한다).
     *
     * @param jobs          API → 워커 처리 요청
     * @param results       워커 → API 처리 결과
     * @param resultsGroup  API 서버들의 소비 그룹
     */
    public record Streams(@DefaultValue("famlog:media") String jobs,
            @DefaultValue("famlog:media-results") String results,
            @DefaultValue("famlog-api") String resultsGroup) {
    }
}
