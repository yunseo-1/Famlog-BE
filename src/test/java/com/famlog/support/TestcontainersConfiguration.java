package com.famlog.support;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * 통합 테스트용 PostgreSQL·Redis·SeaweedFS(S3). PostgreSQL은 로컬 인프라와 같은 이미지(infra/postgres/Dockerfile: PostGIS + pgvector)를
 * 빌드해 쓰고, Redis는 infra/docker-compose.yml 과 같은 버전을 쓴다.
 * SeaweedFS는 로컬 인프라와 같은 시작 스크립트(infra/seaweedfs/entrypoint.sh)로 자격 증명을 켜고, 테스트용 버킷을 만든다.
 * 스프링 컨텍스트 캐시로 테스트 클래스들이 컨테이너를 공유한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String IMAGE_NAME = "famlog/postgres:16-postgis-pgvector";
    public static final String MEDIA_BUCKET = "famlog-media-test";
    static final String S3_ACCESS_KEY = "test-access";
    static final String S3_SECRET_KEY = "test-secret-key";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(famlogPostgresImage());
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> seaweedfs() {
        Path entrypoint = Path.of(System.getProperty("famlog.repoRoot", "."), "infra", "seaweedfs", "entrypoint.sh");
        return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.53"))
                .withCopyFileToContainer(MountableFile.forHostPath(entrypoint), "/famlog/entrypoint.sh")
                .withEnv("S3_ACCESS_KEY", S3_ACCESS_KEY)
                .withEnv("S3_SECRET_KEY", S3_SECRET_KEY)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "/famlog/entrypoint.sh"))
                .withExposedPorts(8333)
                .waitingFor(Wait.forHttp("/healthz").forPort(8333).withStartupTimeout(Duration.ofSeconds(90)));
    }

    /** 앱의 S3 설정을 컨테이너 주소로 바꾼다. 테스트에서는 앱에 주는 주소도 같다 */
    @Bean
    DynamicPropertyRegistrar s3Properties(@Qualifier("seaweedfs") GenericContainer<?> seaweedfs) {
        return registry -> {
            String endpoint = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
            registry.add("famlog.media.s3.endpoint", () -> endpoint);
            registry.add("famlog.media.s3.public-endpoint", () -> endpoint);
            registry.add("famlog.media.s3.access-key", () -> S3_ACCESS_KEY);
            registry.add("famlog.media.s3.secret-key", () -> S3_SECRET_KEY);
            registry.add("famlog.media.s3.bucket", () -> MEDIA_BUCKET);
        };
    }

    @Bean
    ApplicationRunner createMediaBucket(S3Client s3) {
        return args -> {
            try {
                s3.createBucket(builder -> builder.bucket(MEDIA_BUCKET));
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
                // 이미 있음
            }
        };
    }

    private static DockerImageName famlogPostgresImage() {
        Path dockerfile = Path.of(System.getProperty("famlog.repoRoot", "."), "infra", "postgres", "Dockerfile");
        String built = new ImageFromDockerfile(IMAGE_NAME, false).withDockerfile(dockerfile).get();
        return DockerImageName.parse(built).asCompatibleSubstituteFor("postgres");
    }
}
