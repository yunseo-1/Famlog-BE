package com.famlog.media.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.famlog.media.service.MediaProperties;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 미디어 버킷 접근. 버킷은 비공개이고, 앱은 이 클래스가 만든 Presigned URL로만 파일을 주고받는다 (사양서 9.1). */
@Component
@RequiredArgsConstructor
public class MediaStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final MediaProperties properties;

    /** 업로드용 PUT URL. Content-Type·Content-Length를 서명에 넣어 앱이 신고한 값으로만 올릴 수 있다 */
    public URI presignPut(String key, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket()).key(key)
                .contentType(contentType).contentLength(contentLength).build();
        return toUri(presigner.presignPutObject(builder -> builder.signatureDuration(ttl).putObjectRequest(request))
                .url().toString());
    }

    public URI presignGet(String key, Duration ttl) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket()).key(key).build();
        return toUri(presigner.presignGetObject(builder -> builder.signatureDuration(ttl).getObjectRequest(request))
                .url().toString());
    }

    /** 받을 때 저장할 파일 이름을 붙인 GET URL (내보내기 ZIP) */
    public URI presignDownload(String key, Duration ttl, String fileName) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket()).key(key)
                .responseContentDisposition("attachment; filename=\"" + fileName + "\"").build();
        return toUri(presigner.presignGetObject(builder -> builder.signatureDuration(ttl).getObjectRequest(request))
                .url().toString());
    }

    /** 서버가 만든 파일을 올린다 (내보내기 ZIP) */
    public void putFile(String key, Path file, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromFile(file));
    }

    /** 올라온 파일 크기. 없으면 empty */
    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket()).key(key).build())
                    .contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** 파일 앞부분 (형식 판별용) */
    public byte[] readHead(String key, int bytes) {
        ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket())
                .key(key).range("bytes=0-" + (bytes - 1)).build());
        return response.asByteArray();
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build());
    }

    private String bucket() {
        return properties.s3().bucket();
    }

    private static URI toUri(String url) {
        return URI.create(url);
    }
}
