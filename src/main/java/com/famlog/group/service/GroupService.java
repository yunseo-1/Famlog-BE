package com.famlog.group.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.domain.ChatRoom;
import com.famlog.chat.repository.ChatRoomRepository;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.FamilyGroup;
import com.famlog.group.domain.GroupInvite;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.dto.GroupDtos.GroupDetailResponse;
import com.famlog.group.dto.GroupDtos.GroupSummaryResponse;
import com.famlog.group.dto.GroupDtos.InviteResponse;
import com.famlog.group.dto.GroupDtos.MemberResponse;
import com.famlog.group.dto.GroupDtos.UpdateMyMemberRequest;
import com.famlog.group.event.GroupMemberEvents.MemberJoined;
import com.famlog.group.event.GroupMemberEvents.MemberLeft;
import com.famlog.group.repository.FamilyGroupRepository;
import com.famlog.group.repository.GroupInviteRepository;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.user.domain.User;
import com.famlog.user.repository.UserRepository;

/**
 * 가족 그룹 (사양서 2장, 4.1 GRP-01~05). 권한 규칙은 2.2 매트릭스를 따른다.
 * 경로에 groupId가 있는 API는 GroupPathAccessFilter가 이미 구성원 여부를 확인했지만, 서비스도 다시 확인한다
 * (필터를 거치지 않는 호출 경로가 생겨도 안전하게).
 */
@Service
public class GroupService {

    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupInviteRepository inviteRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final GroupAccessChecker accessChecker;
    private final InviteAttemptLimiter inviteAttemptLimiter;
    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher events;
    private final GroupProperties properties;
    private final List<String> palette;
    private final Clock clock;

    public GroupService(FamilyGroupRepository groupRepository, GroupMemberRepository memberRepository,
            GroupInviteRepository inviteRepository, ChatRoomRepository chatRoomRepository,
            UserRepository userRepository, GroupAccessChecker accessChecker,
            InviteAttemptLimiter inviteAttemptLimiter, AuditLogger auditLogger, ApplicationEventPublisher events,
            GroupProperties properties, Clock clock) {
        if (properties.calendarPalette() == null || properties.calendarPalette().isEmpty()) {
            throw new IllegalStateException("famlog.group.calendar-palette 가 비어 있습니다.");
        }
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.accessChecker = accessChecker;
        this.inviteAttemptLimiter = inviteAttemptLimiter;
        this.auditLogger = auditLogger;
        this.events = events;
        this.properties = properties;
        this.palette = properties.calendarPalette().stream().map(color -> color.toUpperCase(Locale.ROOT)).toList();
        this.clock = clock;
    }

    // --- GRP-01 생성·조회·삭제 ---

    /** 생성자는 OWNER, 단체 채팅방을 함께 만들고 현재 그룹으로 지정한다 */
    @Transactional
    public GroupDetailResponse create(long userId, String name) {
        User user = lockUser(userId);
        requireGroupSlot(userId);
        Instant now = Instant.now(clock);
        FamilyGroup group = groupRepository.save(new FamilyGroup(name.strip(), userId));
        memberRepository.save(new GroupMember(group.getId(), userId, GroupRole.OWNER, palette.get(0), now));
        chatRoomRepository.save(new ChatRoom(group.getId()));
        user.changeCurrentGroup(group.getId());
        return detail(group.getId(), userId);
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryResponse> myGroups(long userId) {
        return memberRepository.findMyGroups(userId).stream()
                .map(row -> {
                    FamilyGroup group = (FamilyGroup) row[0];
                    GroupMember member = (GroupMember) row[1];
                    return new GroupSummaryResponse(group.getId(), group.getName(), group.getImageKey(),
                            (Long) row[2], member.getRole());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupDetailResponse detail(long groupId, long userId) {
        GroupRole myRole = accessChecker.requireMember(groupId, userId);
        FamilyGroup group = groupRepository.findById(groupId).orElseThrow();
        List<GroupMember> members = memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(groupId);
        Map<Long, User> users = userRepository.findAllById(members.stream().map(GroupMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return new GroupDetailResponse(group.getId(), group.getName(), group.getImageKey(), myRole,
                members.stream().map(member -> MemberResponse.of(member, users.get(member.getUserId()))).toList());
    }

    /** 그룹 삭제 (OWNER). 즉시 접근을 막고 유예 기간 뒤 파기한다 */
    @Transactional
    public void delete(long groupId, long userId) {
        accessChecker.requireRole(groupId, userId, GroupRole.OWNER);
        scheduleDeletion(lockGroup(groupId), userId, "OWNER_DELETED");
    }

    // --- GRP-02 초대 ---

    /** 초대 코드 발급·재발급 (OWNER·ADMIN). 이전 코드는 바로 못 쓰게 된다 */
    @Transactional
    public InviteResponse issueInvite(long groupId, long userId) {
        accessChecker.requireRole(groupId, userId, GroupRole.ADMIN);
        Instant now = Instant.now(clock);
        inviteRepository.findByGroupIdAndRevokedAtIsNull(groupId).forEach(invite -> invite.revoke(now));
        String code = newUniqueCode();
        GroupInvite invite = inviteRepository.save(
                new GroupInvite(groupId, code, userId, now.plus(properties.inviteTtl())));
        return new InviteResponse(code, properties.inviteLinkBaseUrl() + code, invite.getExpiresAt());
    }

    /**
     * 초대 코드로 가입 (MEMBER). 없는 코드·만료된 코드는 연속 실패로 세고, 한도에 닿으면 잠근다.
     * 이미 구성원이면 그대로 그룹을 돌려준다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public GroupDetailResponse join(long userId, String rawCode) {
        inviteAttemptLimiter.checkNotLocked(userId);
        Instant now = Instant.now(clock);
        GroupInvite invite = InviteCodes.normalize(rawCode).flatMap(inviteRepository::findByCode).orElse(null);
        if (invite == null) {
            inviteAttemptLimiter.recordFailure(userId);
            throw new BusinessException(ErrorCode.NOT_FOUND, "초대 코드를 다시 확인해 주세요.");
        }
        FamilyGroup group = lockGroup(invite.getGroupId());
        if (!invite.isUsable(now) || !group.isActive()) {
            inviteAttemptLimiter.recordFailure(userId);
            throw new BusinessException(ErrorCode.INVITE_EXPIRED);
        }
        inviteAttemptLimiter.reset(userId);

        GroupMember existing = memberRepository.findByGroupIdAndUserId(group.getId(), userId).orElse(null);
        if (existing != null && existing.isActive()) {
            return detail(group.getId(), userId);
        }
        User user = lockUser(userId);
        requireGroupSlot(userId);
        if (memberRepository.countByGroupIdAndLeftAtIsNull(group.getId()) >= properties.maxMembers()) {
            throw new BusinessException(ErrorCode.GROUP_LIMIT_EXCEEDED,
                    "이 가족 그룹은 " + properties.maxMembers() + "명까지 함께할 수 있어요.");
        }
        if (existing != null) {
            existing.rejoin(now);
        } else {
            memberRepository.save(new GroupMember(group.getId(), userId, GroupRole.MEMBER,
                    nextColor(group.getId()), now));
        }
        invite.markUsed();
        user.changeCurrentGroup(group.getId());
        events.publishEvent(new MemberJoined(group.getId(), userId, existing != null));
        return detail(group.getId(), userId);
    }

    // --- GRP-03 현재 그룹 ---

    @Transactional
    public void changeCurrentGroup(long userId, long groupId) {
        accessChecker.requireMember(groupId, userId);
        userRepository.findById(userId).orElseThrow().changeCurrentGroup(groupId);
    }

    // --- GRP-04 내 가족 역할·호칭·색 ---

    @Transactional
    public MemberResponse updateMyProfile(long groupId, long userId, UpdateMyMemberRequest request) {
        accessChecker.requireMember(groupId, userId);
        String color = request.calendarColor() == null ? null : request.calendarColor().toUpperCase(Locale.ROOT);
        if (color != null && !palette.contains(color)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "정해진 색 중에서 골라 주세요.");
        }
        GroupMember member = activeMember(groupId, userId);
        member.updateProfile(request.familyRole(), request.familyTitle(), color);
        return MemberResponse.of(member, userRepository.findById(userId).orElseThrow());
    }

    // --- GRP-05 권한 변경·위임·내보내기·나가기 ---

    /**
     * 권한 변경 (OWNER·ADMIN). OWNER로 바꾸면 그룹장 위임이며 OWNER만 할 수 있다 (이전 OWNER는 ADMIN).
     * ADMIN은 OWNER의 권한을 바꿀 수 없다. 자기 자신은 대상이 아니다.
     */
    @Transactional
    public MemberResponse changeRole(long groupId, long actorId, long targetUserId, GroupRole newRole) {
        GroupRole actorRole = accessChecker.requireRole(groupId, actorId, GroupRole.ADMIN);
        if (actorId == targetUserId) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "자기 자신의 권한은 바꿀 수 없어요.");
        }
        lockGroup(groupId);
        GroupMember target = activeMemberOrNotFound(groupId, targetUserId);
        GroupRole before = target.getRole();

        if (newRole == GroupRole.OWNER) {
            if (actorRole != GroupRole.OWNER) {
                throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "그룹장만 그룹장을 넘길 수 있어요.");
            }
            GroupMember actor = activeMember(groupId, actorId);
            actor.changeRole(GroupRole.ADMIN);
            // 그룹당 OWNER 하나(부분 유니크 인덱스)라서 이전 OWNER를 먼저 반영한다
            memberRepository.saveAndFlush(actor);
            target.changeRole(GroupRole.OWNER);
            auditLogger.record(actorId, AuditAction.GROUP_OWNER_TRANSFERRED, "USER", targetUserId, groupId,
                    Map.of("from", actorId, "to", targetUserId));
        } else {
            if (before == GroupRole.OWNER) {
                throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "그룹장의 권한은 바꿀 수 없어요.");
            }
            target.changeRole(newRole);
            auditLogger.record(actorId, AuditAction.GROUP_ROLE_CHANGED, "USER", targetUserId, groupId,
                    Map.of("before", before.name(), "after", newRole.name()));
        }
        return MemberResponse.of(target, userRepository.findById(targetUserId).orElseThrow());
    }

    /** 내보내기 (OWNER·ADMIN, OWNER는 내보낼 수 없음). 내보내진 사람은 다음 요청부터 그룹에 접근할 수 없다 */
    @Transactional
    public void kick(long groupId, long actorId, long targetUserId) {
        accessChecker.requireRole(groupId, actorId, GroupRole.ADMIN);
        if (actorId == targetUserId) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "자기 자신은 내보낼 수 없어요. 나가기를 이용해 주세요.");
        }
        GroupMember target = activeMemberOrNotFound(groupId, targetUserId);
        if (target.getRole() == GroupRole.OWNER) {
            throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "그룹장은 내보낼 수 없어요.");
        }
        removeMember(target);
        auditLogger.record(actorId, AuditAction.GROUP_MEMBER_KICKED, "USER", targetUserId, groupId, null);
        events.publishEvent(new MemberLeft(groupId, targetUserId, true));
    }

    /**
     * 나가기. 다른 구성원이 있는 OWNER는 먼저 위임해야 한다.
     * 혼자 남은 OWNER가 나가면 그룹은 삭제 예정이 된다 (사양서 2.2).
     */
    @Transactional
    public void leave(long groupId, long userId) {
        GroupRole role = accessChecker.requireMember(groupId, userId);
        FamilyGroup group = lockGroup(groupId);
        GroupMember member = activeMember(groupId, userId);
        if (role == GroupRole.OWNER) {
            if (memberRepository.countByGroupIdAndLeftAtIsNull(groupId) > 1) {
                throw new BusinessException(ErrorCode.OWNER_TRANSFER_REQUIRED);
            }
            removeMember(member);
            scheduleDeletion(group, userId, "LAST_OWNER_LEFT");
            return;
        }
        removeMember(member);
        events.publishEvent(new MemberLeft(groupId, userId, false));
    }

    private void removeMember(GroupMember member) {
        member.leave(Instant.now(clock));
        userRepository.findById(member.getUserId()).orElseThrow().clearCurrentGroupIf(member.getGroupId());
    }

    private void scheduleDeletion(FamilyGroup group, long actorId, String reason) {
        Instant now = Instant.now(clock);
        group.scheduleDeletion(now, properties.deletionGrace());
        inviteRepository.findByGroupIdAndRevokedAtIsNull(group.getId()).forEach(invite -> invite.revoke(now));
        groupRepository.saveAndFlush(group);
        userRepository.clearCurrentGroup(group.getId());
        auditLogger.record(actorId, AuditAction.GROUP_DELETION_SCHEDULED, "GROUP", group.getId(), group.getId(),
                Map.of("reason", reason, "deleteScheduledAt", group.getDeleteScheduledAt().toString()));
    }

    // --- 공통 ---

    private void requireGroupSlot(long userId) {
        if (memberRepository.countActiveGroupsOf(userId) >= properties.maxGroupsPerUser()) {
            throw new BusinessException(ErrorCode.GROUP_LIMIT_EXCEEDED,
                    "가족 그룹은 " + properties.maxGroupsPerUser() + "개까지 참여할 수 있어요.");
        }
    }

    /** 그룹 안에서 아직 안 쓰인 첫 색. 모두 쓰였으면 인원 수로 돌려 쓴다 */
    private String nextColor(long groupId) {
        List<GroupMember> members = memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(groupId);
        Set<String> used = members.stream().map(GroupMember::getCalendarColor).collect(Collectors.toSet());
        return palette.stream().filter(color -> !used.contains(color)).findFirst()
                .orElse(palette.get(members.size() % palette.size()));
    }

    private String newUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = InviteCodes.generate();
            if (!inviteRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("초대 코드를 만들지 못했습니다");
    }

    private User lockUser(long userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID));
    }

    private FamilyGroup lockGroup(long groupId) {
        return groupRepository.findByIdForUpdate(groupId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private GroupMember activeMember(long groupId, long userId) {
        return memberRepository.findByGroupIdAndUserId(groupId, userId).filter(GroupMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_MEMBER));
    }

    private GroupMember activeMemberOrNotFound(long groupId, long userId) {
        return memberRepository.findByGroupIdAndUserId(groupId, userId).filter(GroupMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그룹에 없는 구성원이에요."));
    }
}
