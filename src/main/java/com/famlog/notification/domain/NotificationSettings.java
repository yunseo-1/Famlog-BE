package com.famlog.notification.domain;

import java.time.LocalTime;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 단위 알림 설정 (사양서 4.1 SET-02, V6 user_notification_settings).
 * 행이 없으면 {@link #defaults(long)} 값으로 본다. 방해 금지 시각은 Asia/Seoul 기준이고 자정을 넘길 수 있다 (22:00~07:00).
 */
@Getter
@Entity
@Table(name = "user_notification_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSettings extends BaseTimeEntity {

    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(7, 0);

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "preview_hidden", nullable = false)
    private boolean previewHidden;

    @Column(name = "quiet_enabled", nullable = false)
    private boolean quietEnabled;

    @Column(name = "quiet_start", nullable = false)
    private LocalTime quietStart;

    @Column(name = "quiet_end", nullable = false)
    private LocalTime quietEnd;

    private NotificationSettings(long userId) {
        this.userId = userId;
        this.quietStart = DEFAULT_QUIET_START;
        this.quietEnd = DEFAULT_QUIET_END;
    }

    /** 미리보기 보이기, 방해 금지 꺼짐 (켜면 22:00~07:00) */
    public static NotificationSettings defaults(long userId) {
        return new NotificationSettings(userId);
    }

    /** null 인 값은 바꾸지 않는다 */
    public void update(Boolean previewHidden, Boolean quietEnabled, LocalTime quietStart, LocalTime quietEnd) {
        if (previewHidden != null) {
            this.previewHidden = previewHidden;
        }
        if (quietEnabled != null) {
            this.quietEnabled = quietEnabled;
        }
        if (quietStart != null) {
            this.quietStart = quietStart;
        }
        if (quietEnd != null) {
            this.quietEnd = quietEnd;
        }
    }

    /** 지금(서울 시각)이 방해 금지 시간인가. 시작은 포함, 끝은 제외 */
    public boolean isQuietAt(LocalTime now) {
        if (!quietEnabled) {
            return false;
        }
        if (quietStart.isBefore(quietEnd)) {
            return !now.isBefore(quietStart) && now.isBefore(quietEnd);
        }
        return !now.isBefore(quietStart) || now.isBefore(quietEnd);
    }
}
