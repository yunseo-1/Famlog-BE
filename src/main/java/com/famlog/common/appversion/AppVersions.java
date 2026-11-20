package com.famlog.common.appversion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 플랫폼별 최소 지원·최신 버전과 공지 (app_versions, 사양서 14장 강제 업데이트·점검 공지) */
@Component
@RequiredArgsConstructor
public class AppVersions {

    public static final String ANDROID = "ANDROID";
    public static final List<String> PLATFORMS = List.of("ANDROID", "IOS");

    private final JdbcTemplate jdbcTemplate;

    /** @param noticeUpdatedAt 공지를 바꾼 시각 (공지가 없으면 null). 앱은 이 값으로 이미 본 공지인지 판단한다 */
    public record AppVersion(String platform, String minSupportedVersion, String latestVersion, String notice,
            Instant noticeUpdatedAt) {
    }

    private static final String SELECT = """
            SELECT platform, min_supported_version, latest_version, notice, notice_updated_at FROM app_versions
            """;
    private static final RowMapper<AppVersion> ROW = (rs, i) -> new AppVersion(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getString(4),
            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant());

    public Optional<AppVersion> find(String platform) {
        return jdbcTemplate.query(SELECT + " WHERE platform = ?", ROW, platform).stream().findFirst();
    }

    public List<AppVersion> all() {
        return jdbcTemplate.query(SELECT + " ORDER BY platform", ROW);
    }

    /** 공지 문구가 바뀌면 notice_updated_at 을 지금으로, 공지를 지우면 null 로 */
    public AppVersion save(String platform, String minSupportedVersion, String latestVersion, String notice) {
        jdbcTemplate.update("""
                INSERT INTO app_versions (platform, min_supported_version, latest_version, notice, notice_updated_at)
                VALUES (?, ?, ?, ?, CASE WHEN ?::varchar IS NULL THEN NULL ELSE now() END)
                ON CONFLICT (platform) DO UPDATE SET min_supported_version = EXCLUDED.min_supported_version,
                    latest_version = EXCLUDED.latest_version, notice = EXCLUDED.notice, updated_at = now(),
                    notice_updated_at = CASE WHEN EXCLUDED.notice IS NULL THEN NULL
                        WHEN app_versions.notice IS DISTINCT FROM EXCLUDED.notice THEN now()
                        ELSE app_versions.notice_updated_at END
                """, platform, minSupportedVersion, latestVersion, notice, notice);
        return find(platform).orElseThrow();
    }

    /** "1.2.10" > "1.2.9". 숫자가 아닌 꼬리("-beta")는 무시한다. 읽을 수 없으면 0 */
    public static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? x[i] : 0;
            int right = i < y.length ? y[i] : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    public static boolean isVersion(String value) {
        return value != null && value.matches("\\d{1,4}(\\.\\d{1,4}){0,3}");
    }

    private static int[] parts(String version) {
        String[] tokens = version == null ? new String[0] : version.split("[-+]")[0].split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i]);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}
