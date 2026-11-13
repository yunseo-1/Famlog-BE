package com.famlog.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.famlog.auth.jwt.FamlogJwtClaims;

/**
 * 통합 테스트 베이스: 전체 컨텍스트(보안 필터 체인 포함) + Testcontainers PostgreSQL·Redis + Flyway 마이그레이션.
 * 모든 통합 테스트가 같은 설정을 써서 컨텍스트 하나(컨테이너 한 벌)를 공유한다.
 * Docker가 필요하다 (로컬: OrbStack, CI: GitHub Actions ubuntu 러너).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, IntegrationTest.TestBeans.class })
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected FakeGroupAccessChecker groupAccessChecker;

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        registry.add("famlog.auth.jwt.active-kid", () -> TestJwtKeys.ACTIVE_KID);
        registry.add("famlog.auth.jwt.keys[0].kid", () -> TestJwtKeys.ACTIVE_KID);
        registry.add("famlog.auth.jwt.keys[0].private-key", () -> TestJwtKeys.privateKey(TestJwtKeys.ACTIVE));
        registry.add("famlog.auth.jwt.keys[0].public-key", () -> TestJwtKeys.publicKey(TestJwtKeys.ACTIVE));
        // 교체 전 키: 검증용 공개 키만
        registry.add("famlog.auth.jwt.keys[1].kid", () -> TestJwtKeys.PREVIOUS_KID);
        registry.add("famlog.auth.jwt.keys[1].public-key", () -> TestJwtKeys.publicKey(TestJwtKeys.PREVIOUS));
    }

    /** 사용자 행을 만든다 (세션 등 FK 대상). User 엔티티는 S4에서 생긴다 */
    protected long createUser() {
        return jdbcTemplate.queryForObject("INSERT INTO users (nickname) VALUES ('테스트') RETURNING id", Long.class);
    }

    /** 토큰 검증을 건너뛰고 인증된 사용자로 요청한다 (인증 자체를 시험하지 않는 테스트용) */
    protected static RequestPostProcessor asUser(long userId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim(FamlogJwtClaims.SESSION_ID, "1"));
    }

    /** 테스트끼리 키·그룹이 겹치지 않게 쓰는 무작위 양수 */
    protected static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        FakeGroupAccessChecker fakeGroupAccessChecker() {
            return new FakeGroupAccessChecker();
        }
    }
}
