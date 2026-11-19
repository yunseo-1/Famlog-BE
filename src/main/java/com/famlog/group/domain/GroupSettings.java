package com.famlog.group.domain;

import java.time.Instant;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹 설정 (사양서 4.1 SET-01). 행이 없으면 기본값: AI 분석 꺼짐 (8장 "기본값은 보호").
 * AI 분석을 켠 시각 이전의 메시지는 분석하지 않는다.
 */
@Getter
@Entity
@Table(name = "group_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupSettings extends BaseTimeEntity {

    @Id
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "ai_analysis_enabled", nullable = false)
    private boolean aiAnalysisEnabled;

    @Column(name = "ai_enabled_by")
    private Long aiEnabledBy;

    @Column(name = "ai_enabled_at")
    private Instant aiEnabledAt;

    public static GroupSettings defaults(long groupId) {
        GroupSettings settings = new GroupSettings();
        settings.groupId = groupId;
        return settings;
    }

    /** @return 꺼져 있다가 켜졌으면 true */
    public boolean changeAiAnalysis(boolean enabled, long actorId, Instant now) {
        boolean turnedOn = enabled && !aiAnalysisEnabled;
        if (turnedOn) {
            aiEnabledBy = actorId;
            aiEnabledAt = now;
        }
        aiAnalysisEnabled = enabled;
        return turnedOn;
    }
}
