package com.famlog.admin.service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.admin.dto.AdminDtos.AddHolidayRequest;
import com.famlog.admin.dto.AdminDtos.AdminHoliday;
import com.famlog.admin.dto.AdminDtos.AuditLogItem;
import com.famlog.admin.dto.AdminDtos.FeatureFlagItem;
import com.famlog.admin.dto.AdminDtos.UpdateAppVersionRequest;
import com.famlog.candidate.service.AiProperties;
import com.famlog.common.appversion.AppVersions;
import com.famlog.common.appversion.AppVersions.AppVersion;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.flag.FeatureFlag;
import com.famlog.common.flag.FeatureFlags;
import com.famlog.media.service.MediaProperties;
import com.famlog.place.service.PlaceProperties;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 도구의 공휴일·앱 버전·기능 플래그·감사 로그 (사양서 14장). 바꿀 때마다 감사 로그를 남긴다.
 * 기능 플래그는 DB 값이 없으면 설정값(application.yml)을 따른다
 */
@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    static final int MAX_AUDIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final AppVersions appVersions;
    private final FeatureFlags featureFlags;
    private final PlaceProperties placeProperties;
    private final MediaProperties mediaProperties;
    private final AiProperties aiProperties;
    private final AuditLogger auditLogger;

    // --- 공휴일 ---

    @Transactional(readOnly = true)
    public List<AdminHoliday> holidays(int year) {
        return jdbcTemplate.query("""
                SELECT id, date, name, is_substitute FROM holidays
                WHERE date >= make_date(?, 1, 1) AND date <= make_date(?, 12, 31) ORDER BY date, id
                """, (rs, i) -> new AdminHoliday(rs.getLong(1), rs.getObject(2, LocalDate.class),
                rs.getString(3), rs.getBoolean(4)), year, year);
    }

    /** 같은 날짜·이름이 이미 있으면 그대로 돌려준다 */
    @Transactional
    public AdminHoliday addHoliday(AddHolidayRequest request) {
        String name = request.name().strip();
        jdbcTemplate.update("""
                INSERT INTO holidays (date, name, is_substitute) VALUES (?, ?, ?) ON CONFLICT (date, name) DO NOTHING
                """, request.date(), name, request.substitute());
        AdminHoliday holiday = jdbcTemplate.queryForObject("""
                SELECT id, date, name, is_substitute FROM holidays WHERE date = ? AND name = ?
                """, (rs, i) -> new AdminHoliday(rs.getLong(1), rs.getObject(2, LocalDate.class),
                rs.getString(3), rs.getBoolean(4)), request.date(), name);
        auditLogger.recordAdmin(AuditAction.ADMIN_HOLIDAY_ADDED, "HOLIDAY", holiday.id(), null,
                Map.of("date", request.date().toString()));
        return holiday;
    }

    @Transactional
    public void deleteHoliday(long id) {
        if (jdbcTemplate.update("DELETE FROM holidays WHERE id = ?", id) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditLogger.recordAdmin(AuditAction.ADMIN_HOLIDAY_DELETED, "HOLIDAY", id, null, null);
    }

    // --- 앱 버전 ---

    @Transactional(readOnly = true)
    public List<AppVersion> appVersions() {
        return appVersions.all();
    }

    @Transactional
    public AppVersion updateAppVersion(String platform, UpdateAppVersionRequest request) {
        if (!AppVersions.PLATFORMS.contains(platform)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String latest = blankToNull(request.latestVersion());
        if (!AppVersions.isVersion(request.minSupportedVersion())
                || (latest != null && !AppVersions.isVersion(latest))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "버전은 1.2.3 처럼 숫자와 점으로 적어 주세요.");
        }
        if (latest != null && AppVersions.compare(latest, request.minSupportedVersion()) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "최신 버전이 최소 지원 버전보다 낮아요.");
        }
        AppVersion version = appVersions.save(platform, request.minSupportedVersion(), latest,
                blankToNull(request.notice()));
        auditLogger.recordAdmin(AuditAction.ADMIN_APP_VERSION_CHANGED, "APP_VERSION", null, null,
                Map.of("platform", platform, "minSupportedVersion", version.minSupportedVersion()));
        return version;
    }

    // --- 기능 플래그 ---

    @Transactional(readOnly = true)
    public List<FeatureFlagItem> featureFlags() {
        return Arrays.stream(FeatureFlag.values()).map(this::item).toList();
    }

    @Transactional
    public FeatureFlagItem updateFeatureFlag(String key, boolean enabled) {
        FeatureFlag flag = FeatureFlag.ofKey(key);
        if (flag == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        featureFlags.set(flag, enabled);
        auditLogger.recordAdmin(AuditAction.ADMIN_FEATURE_FLAG_CHANGED, "FEATURE_FLAG", null, null,
                Map.of("key", key, "enabled", enabled));
        return item(flag);
    }

    /** 앱에 내려주는 기능 상태 (GET /app/config) */
    @Transactional(readOnly = true)
    public Map<String, Boolean> features() {
        return Map.of("nearby", enabled(FeatureFlag.PLACE_NEARBY), "photoLocation",
                enabled(FeatureFlag.MEDIA_PHOTO_LOCATION), "aiAnalysis", enabled(FeatureFlag.AI_ANALYSIS));
    }

    private boolean enabled(FeatureFlag flag) {
        return featureFlags.enabled(flag, fallback(flag));
    }

    private FeatureFlagItem item(FeatureFlag flag) {
        return featureFlags.stored(flag)
                .map(value -> new FeatureFlagItem(flag.key(), value, "DB"))
                .orElseGet(() -> new FeatureFlagItem(flag.key(), fallback(flag), "DEFAULT"));
    }

    private boolean fallback(FeatureFlag flag) {
        return switch (flag) {
            case PLACE_NEARBY -> placeProperties.nearbyEnabled();
            case MEDIA_PHOTO_LOCATION -> mediaProperties.photoLocationEnabled();
            case AI_ANALYSIS -> aiProperties.enabled();
        };
    }

    // --- 감사 로그 ---

    @Transactional(readOnly = true)
    public List<AuditLogItem> auditLogs(Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_AUDIT);
        return jdbcTemplate.query("""
                SELECT id, actor_id, action, target_type, target_id, group_id, meta::text, ip, created_at
                FROM audit_logs ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new AuditLogItem(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3),
                rs.getString(4), rs.getObject(5, Long.class), rs.getObject(6, Long.class), rs.getString(7),
                rs.getString(8), rs.getTimestamp(9).toInstant()), size);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
