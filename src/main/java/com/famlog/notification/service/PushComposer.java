package com.famlog.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.auth.service.NewDeviceLoggedIn;
import com.famlog.chat.domain.Message;
import com.famlog.chat.domain.MessageType;
import com.famlog.chat.event.MessagePosted;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.FamilyGroup;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.event.GroupMemberEvents.MemberLeft;
import com.famlog.group.repository.FamilyGroupRepository;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.notification.domain.NotificationSettings;
import com.famlog.notification.push.PushData;
import com.famlog.notification.push.PushProperties;
import com.famlog.notification.repository.NotificationSettingsRepository;
import com.famlog.user.domain.User;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받을 사람별 푸시 data 를 만든다 (사양서 4.2 NOTI-01, 4.1 SET-02). 키 목록은 {@link PushData}.
 * <ul>
 * <li>새 메시지: 보낸 사람(시스템 메시지는 그 대상), 나간 구성원, 그룹 알림을 끈 구성원, 메시지보다 늦게 들어온 구성원은 뺀다</li>
 * <li>미리보기를 숨긴 사람에게는 body 를 넣지 않는다. 방해 금지 시간이면 silent=true</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class PushComposer {

    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MessageRepository messageRepository;
    private final FamilyGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final PushProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Map<Long, Map<String, String>> chatMessage(MessagePosted event) {
        Message message = messageRepository.findById(event.messageId()).filter(m -> !m.isDeleted()).orElse(null);
        FamilyGroup group = groupRepository.findById(event.groupId()).filter(FamilyGroup::isActive).orElse(null);
        if (message == null || group == null) {
            return Map.of();
        }
        Long subjectId = message.getSenderId() != null ? message.getSenderId() : payload(message).path("userId").asLong();
        List<GroupMember> members = memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(group.getId());
        List<Long> recipients = members.stream()
                .filter(member -> !member.getUserId().equals(subjectId))
                .filter(member -> !member.isNotifMuted())
                .filter(member -> member.getJoinSeq() < message.getSeq())
                .map(GroupMember::getUserId)
                .toList();
        if (recipients.isEmpty()) {
            return Map.of();
        }

        Map<String, String> common = base(PushData.CHAT_MESSAGE, PushData.CHANNEL_CHAT, group, message.getCreatedAt());
        common.put(PushData.MESSAGE_ID, message.getId().toString());
        common.put(PushData.SEQ, Long.toString(message.getSeq()));
        common.put(PushData.MESSAGE_TYPE, message.getType().name());
        if (message.getSenderId() != null) {
            common.put(PushData.SENDER_ID, message.getSenderId().toString());
            common.put(PushData.SENDER_NAME, senderName(members, message.getSenderId()));
        }
        if (message.getType() == MessageType.IMAGE || message.getType() == MessageType.VIDEO) {
            common.put(PushData.MEDIA_COUNT, Integer.toString(payload(message).path("mediaIds").size()));
        }
        String preview = message.getType() == MessageType.TEXT || message.getType() == MessageType.SYSTEM
                ? truncate(message.getBody()) : null;

        Map<Long, NotificationSettings> settings = settingsOf(recipients);
        LocalTime now = seoulNow();
        Map<Long, Map<String, String>> dataByUser = new HashMap<>();
        for (Long userId : recipients) {
            NotificationSettings mine = settings.get(userId);
            Map<String, String> data = new HashMap<>(common);
            data.put(PushData.SILENT, Boolean.toString(mine.isQuietAt(now)));
            if (preview != null && !mine.isPreviewHidden()) {
                data.put(PushData.BODY, preview);
            }
            dataByUser.put(userId, data);
        }
        return dataByUser;
    }

    /** 내보내진 본인에게 */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, String>> memberRemoved(MemberLeft event) {
        FamilyGroup group = groupRepository.findById(event.groupId()).orElse(null);
        if (group == null) {
            return Map.of();
        }
        Map<String, String> data = base(PushData.MEMBER_REMOVED, PushData.CHANNEL_SYSTEM, group, Instant.now(clock));
        data.put(PushData.SILENT, Boolean.toString(settingsOf(List.of(event.userId())).get(event.userId())
                .isQuietAt(seoulNow())));
        return Map.of(event.userId(), data);
    }

    /** 새 기기로 로그인한 사용자의 다른 기기로 (새 기기는 보내는 쪽에서 뺀다) */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, String>> newDeviceLogin(NewDeviceLoggedIn event) {
        Map<String, String> data = new HashMap<>();
        data.put(PushData.TYPE, PushData.NEW_DEVICE_LOGIN);
        data.put(PushData.CHANNEL, PushData.CHANNEL_SYSTEM);
        data.put(PushData.SESSION_ID, Long.toString(event.sessionId()));
        data.put(PushData.PLATFORM, event.platform().name());
        if (event.deviceName() != null && !event.deviceName().isBlank()) {
            data.put(PushData.DEVICE_NAME, event.deviceName());
        }
        data.put(PushData.SENT_AT, sentAt(Instant.now(clock)));
        data.put(PushData.SILENT, Boolean.toString(settingsOf(List.of(event.userId())).get(event.userId())
                .isQuietAt(seoulNow())));
        return Map.of(event.userId(), data);
    }

    private Map<String, String> base(String type, String channel, FamilyGroup group, Instant at) {
        Map<String, String> data = new HashMap<>();
        data.put(PushData.TYPE, type);
        data.put(PushData.CHANNEL, channel);
        data.put(PushData.GROUP_ID, group.getId().toString());
        data.put(PushData.GROUP_NAME, group.getName());
        data.put(PushData.SENT_AT, sentAt(at));
        return data;
    }

    /** 호칭이 있으면 호칭, 없으면 닉네임 */
    private String senderName(List<GroupMember> members, long senderId) {
        String title = members.stream()
                .filter(member -> member.getUserId() == senderId)
                .map(GroupMember::getFamilyTitle)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return title != null ? title : userRepository.findById(senderId).map(User::getNickname).orElse("가족");
    }

    /** 설정 행이 없는 사람은 기본값 */
    private Map<Long, NotificationSettings> settingsOf(List<Long> userIds) {
        Map<Long, NotificationSettings> found = settingsRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(NotificationSettings::getUserId, Function.identity()));
        return userIds.stream().distinct().collect(Collectors.toMap(Function.identity(),
                userId -> found.getOrDefault(userId, NotificationSettings.defaults(userId))));
    }

    private JsonNode payload(Message message) {
        return message.getPayload() == null ? jsonMapper.createObjectNode() : jsonMapper.readTree(message.getPayload());
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        int max = properties.previewMaxLength();
        if (body.codePointCount(0, body.length()) <= max) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, max)) + "…";
    }

    private LocalTime seoulNow() {
        return LocalTime.now(clock.withZone(FamlogTime.DISPLAY_ZONE));
    }

    private static String sentAt(Instant at) {
        return SENT_AT.format(at.atZone(FamlogTime.DISPLAY_ZONE).toOffsetDateTime());
    }
}
