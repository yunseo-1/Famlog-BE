package com.famlog.group.domain;

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

/**
 * 그룹 구성원 (사양서 10.2 group_members). 나가거나 내보내지면 행을 지우지 않고 leftAt을 채운다
 * (그가 올린 기록은 그룹에 남는다, 사양서 2.2). 다시 들어오면 같은 행을 되살린다.
 */
@Getter
@Entity
@Table(name = "group_members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "family_role")
    private FamilyRole familyRole;

    @Column(name = "family_title")
    private String familyTitle;

    @Column(name = "calendar_color", nullable = false)
    private String calendarColor;

    @Column(name = "ai_opt_out", nullable = false)
    private boolean aiOptOut;

    /** 분석 제외를 마지막으로 바꾼 시각. 이전 메시지는 AI 분석에 보내지 않는다 (제외했던 동안의 메시지 보호) */
    @Column(name = "ai_opt_out_changed_at")
    private Instant aiOptOutChangedAt;

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

    /** 들어온 시점의 채팅 seq. 이보다 큰 seq의 메시지만 볼 수 있다 */
    @Column(name = "join_seq", nullable = false)
    private long joinSeq;

    @Column(name = "notif_muted", nullable = false)
    private boolean notifMuted;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    public GroupMember(Long groupId, Long userId, GroupRole role, String calendarColor, Instant now) {
        this.groupId = groupId;
        this.userId = userId;
        this.role = role;
        this.calendarColor = calendarColor;
        this.joinedAt = now;
    }

    public boolean isActive() {
        return leftAt == null;
    }

    /** 나갔던 그룹에 다시 들어온다. 권한은 MEMBER부터, 가족 역할·호칭·색은 이전 값을 그대로 쓴다 */
    public void rejoin(Instant now) {
        this.role = GroupRole.MEMBER;
        this.joinedAt = now;
        this.leftAt = null;
    }

    public void leave(Instant now) {
        if (leftAt == null) {
            leftAt = now;
        }
    }

    /** 입장 시 채팅 읽기 범위를 정한다: joinSeq 이후 메시지만 보이고, 입장 메시지(readSeq)까지는 읽은 것으로 본다 */
    public void startChatFrom(long joinSeq, long readSeq) {
        this.joinSeq = joinSeq;
        this.lastReadSeq = readSeq;
    }

    /**
     * 읽음 위치를 앞으로만 옮긴다.
     *
     * @return 옮겼으면 이전 값, 이미 그 이상 읽었으면 -1
     */
    public long markReadUpTo(long seq) {
        if (seq <= lastReadSeq) {
            return -1;
        }
        long before = lastReadSeq;
        lastReadSeq = seq;
        return before;
    }

    /** 이 그룹 알림 끄기 (SET-02) */
    public void muteNotifications(boolean muted) {
        this.notifMuted = muted;
    }

    /** 내 메시지 AI 분석 제외 (SET-01, 사양서 8.7). 본인만 바꾼다 */
    public void changeAiOptOut(boolean optOut, Instant now) {
        if (aiOptOut != optOut) {
            aiOptOut = optOut;
            aiOptOutChangedAt = now;
        }
    }

    public void changeRole(GroupRole role) {
        this.role = role;
    }

    /** null 인 값은 바꾸지 않는다. 호칭은 빈 문자열이면 지운다 */
    public void updateProfile(FamilyRole familyRole, String familyTitle, String calendarColor) {
        if (familyRole != null) {
            this.familyRole = familyRole;
        }
        if (familyTitle != null) {
            this.familyTitle = familyTitle.isBlank() ? null : familyTitle.strip();
        }
        if (calendarColor != null) {
            this.calendarColor = calendarColor;
        }
    }
}
