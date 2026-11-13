package com.famlog.support;

import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 PostgreSQL·Redis. PostgreSQL은 로컬 인프라와 같은 이미지(infra/postgres/Dockerfile: PostGIS + pgvector)를
 * 빌드해 쓰고, Redis는 infra/docker-compose.yml 과 같은 버전을 쓴다.
 * 스프링 컨텍스트 캐시로 테스트 클래스들이 컨테이너를 공유한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String IMAGE_NAME = "famlog/postgres:16-postgis-pgvector";

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

    private static DockerImageName famlogPostgresImage() {
        Path dockerfile = Path.of(System.getProperty("famlog.repoRoot", "."), "infra", "postgres", "Dockerfile");
        String built = new ImageFromDockerfile(IMAGE_NAME, false).withDockerfile(dockerfile).get();
        return DockerImageName.parse(built).asCompatibleSubstituteFor("postgres");
    }
}
