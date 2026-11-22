package com.famlog.group.domain;

import java.time.Duration;
import java.time.Instant;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 가족 그룹 (사양서 2.1). 초대 코드로만 가입하는 폐쇄형 그룹. */
@Getter
@Entity
@Table(name = "family_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyGroup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "image_key")
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupStatus status;

    @Column(name = "delete_scheduled_at")
    private Instant deleteScheduledAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    public FamilyGroup(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.status = GroupStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == GroupStatus.ACTIVE;
    }

    /** 삭제 예정을 거둔다 (계정 삭제를 복구할 때, 유예 기간 안이면) */
    public boolean restoreIfScheduled(Instant now) {
        if (status == GroupStatus.PENDING_DELETION && deleteScheduledAt != null && deleteScheduledAt.isAfter(now)) {
            status = GroupStatus.ACTIVE;
            deleteScheduledAt = null;
            return true;
        }
        return false;
    }

    /** 즉시 접근을 막고 유예 기간 뒤 파기 대상이 된다 (사양서 2.2) */
    public void scheduleDeletion(Instant now, Duration grace) {
        if (status == GroupStatus.ACTIVE) {
            status = GroupStatus.PENDING_DELETION;
            deleteScheduledAt = now.plus(grace);
        }
    }

    /** 파기: 그룹 데이터를 지운 뒤 기록용 행만 남긴다 (사양서 8.2). 이름·사진은 남기지 않는다 */
    public void purge(Instant now) {
        status = GroupStatus.DELETED;
        name = "삭제된 그룹";
        imageKey = null;
        purgedAt = now;
    }
}
