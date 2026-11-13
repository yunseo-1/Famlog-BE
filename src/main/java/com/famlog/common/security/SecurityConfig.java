package com.famlog.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.famlog.common.error.ErrorResponseWriter;
import com.famlog.common.ratelimit.RateLimitFilter;
import com.famlog.common.ratelimit.RateLimitProperties;
import com.famlog.common.ratelimit.RateLimiter;
import com.famlog.group.service.GroupAccessChecker;

/**
 * 보안 필터 체인 (사양서 9.2, 9.3, 9.6).
 * 순서: TraceIdFilter(서블릿) → JWT 인증 → 요청 제한 → 그룹 경로 인가 → 컨트롤러.
 * 요청 제한·그룹 인가 필터는 빈으로 등록하지 않는다 (서블릿 필터로 한 번 더 등록되는 것을 막는다).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** 토큰 없이 부를 수 있는 경로. 그 밖은 모두 인증 필요 */
    static final String[] PUBLIC_PATHS = {
            "/error",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/api/v1/auth/**",
            "/api/v1/app/config",
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            FamlogAuthenticationEntryPoint authenticationEntryPoint, FamlogAccessDeniedHandler accessDeniedHandler,
            RateLimitProperties rateLimitProperties, RateLimiter rateLimiter, GroupAccessChecker groupAccessChecker,
            ErrorResponseWriter errorResponseWriter) throws Exception {
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimitProperties, rateLimiter, errorResponseWriter);
        GroupPathAccessFilter groupPathAccessFilter = new GroupPathAccessFilter(groupAccessChecker,
                errorResponseWriter);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(groupPathAccessFilter, RateLimitFilter.class);
        return http.build();
    }
}
