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

    /** 즉시 접근을 막고 유예 기간 뒤 파기 대상이 된다 (사양서 2.2) */
    public void scheduleDeletion(Instant now, Duration grace) {
        if (status == GroupStatus.ACTIVE) {
            status = GroupStatus.PENDING_DELETION;
            deleteScheduledAt = now.plus(grace);
        }
    }
}
