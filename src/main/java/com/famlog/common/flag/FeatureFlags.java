package com.famlog.common.flag;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 기능 플래그 읽기·쓰기 (feature_flags). 값이 없으면 부르는 쪽이 넘긴 설정값을 쓴다 */
@Component
@RequiredArgsConstructor
public class FeatureFlags {

    private final JdbcTemplate jdbcTemplate;

    /** @param fallback DB 값이 없을 때 (application.yml 설정값) */
    public boolean enabled(FeatureFlag flag, boolean fallback) {
        return stored(flag).orElse(fallback);
    }

    /** DB 에 저장된 값 (없으면 빈 값) */
    public Optional<Boolean> stored(FeatureFlag flag) {
        return jdbcTemplate.query("SELECT enabled FROM feature_flags WHERE key = ?",
                (rs, i) -> rs.getBoolean(1), flag.key()).stream().findFirst();
    }

    public void set(FeatureFlag flag, boolean enabled) {
        jdbcTemplate.update("""
                INSERT INTO feature_flags (key, enabled) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                """, flag.key(), enabled);
    }
}
