package com.famlog.media.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.famlog.media.service.MediaProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트. 로컬은 SeaweedFS(경로 방식 주소), 운영은 AWS S3.
 * 서버가 쓰는 주소(endpoint)와 앱에 주는 Presigned URL 주소(publicEndpoint)를 따로 둔다
 * (서명에 호스트가 들어가므로 앱이 접속할 주소로 서명해야 한다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class S3Config {

    @Bean(destroyMethod = "close")
    S3Client s3Client(MediaProperties properties) {
        MediaProperties.S3 s3 = properties.s3();
        var builder = S3Client.builder()
                .region(Region.of(s3.region()))
                .credentialsProvider(credentials(s3))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(hasText(s3.endpoint())).build());
        if (hasText(s3.endpoint())) {
            builder.endpointOverride(URI.create(s3.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(MediaProperties properties) {
        MediaProperties.S3 s3 = properties.s3();
        var builder = S3Presigner.builder()
                .region(Region.of(s3.region()))
                .credentialsProvider(credentials(s3))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(hasText(s3.endpoint())).build());
        if (hasText(s3.presignEndpoint())) {
            builder.endpointOverride(URI.create(s3.presignEndpoint()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(MediaProperties.S3 s3) {
        if (hasText(s3.accessKey()) && hasText(s3.secretKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()));
        }
        // 운영: 인스턴스 역할 등 AWS 기본 자격 증명
        return DefaultCredentialsProvider.builder().build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
