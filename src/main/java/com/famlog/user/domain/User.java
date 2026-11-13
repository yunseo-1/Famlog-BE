package com.famlog.user.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/**
 * 앱 계정 (사양서 10.2 users). 이메일·실명·전화번호는 받지 않는다 (사양서 8.2).
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "profile_image_key")
    private String profileImageKey;

    /** YYYYMM. 만 14세 확인용, 로그에 남기지 않는다 (사양서 9.8) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "birth_ym", length = 6)
    private String birthYm;

    @Column(name = "age_verified_at")
    private Instant ageVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "last_group_id")
    private Long lastGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ui_mode", nullable = false)
    private UiMode uiMode;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 연령 확인을 통과한 가입 (사양서 4.1 AUTH-02: 가입 시 확인) */
    public static User signUp(String nickname, String birthYm, Instant now) {
        User user = new User();
        user.nickname = nickname;
        user.birthYm = birthYm;
        user.ageVerifiedAt = now;
        user.status = UserStatus.ACTIVE;
        user.uiMode = UiMode.DEFAULT;
        return user;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** 현재 가족 그룹 (사양서 4.1 GRP-03). 구성원인지는 호출하는 쪽에서 확인한다 */
    public void changeCurrentGroup(Long groupId) {
        this.lastGroupId = groupId;
    }

    /** 나가거나 내보내진 그룹이 현재 그룹이면 비운다 → 앱이 다른 그룹이나 그룹 시작 화면으로 */
    public void clearCurrentGroupIf(long groupId) {
        if (lastGroupId != null && lastGroupId == groupId) {
            lastGroupId = null;
        }
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", status=" + status + "]";
    }
}
