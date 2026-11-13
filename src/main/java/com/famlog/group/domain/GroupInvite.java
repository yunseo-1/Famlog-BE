package com.famlog.group.domain;

import java.time.Instant;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 초대 코드 (사양서 4.1 GRP-02). 그룹당 쓸 수 있는 코드는 하나: 재발급하면 이전 코드는 revoked. */
@Getter
@Entity
@Table(name = "group_invites")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupInvite extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    public GroupInvite(Long groupId, String code, Long createdBy, Instant expiresAt) {
        this.groupId = groupId;
        this.code = code;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public void markUsed() {
        useCount++;
    }
}
