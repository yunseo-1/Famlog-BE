package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.famlog.candidate.domain.AiTrigger;
import com.famlog.chat.domain.Message;
import com.famlog.chat.domain.MessageType;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupSettingsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대화 분석 트리거 (사양서 4.5 ARC-01, 6.2 ①). 방마다 Redis 에 "분석할 메시지 수·규칙 필터에 걸렸는지"와 분석 시각을 둔다.
 * <ul>
 * <li>분석 대상 메시지(그룹 AI 켜짐, 사람이 보낸 TEXT, 보낸 사람이 분석 제외 안 함)가 오면 수를 올리고 규칙 필터를 본다.
 * 그룹 AI가 꺼졌거나 분석 제외한 사람의 메시지는 세지 않는다 (AR-10)</li>
 * <li>분석 시각: 30개가 쌓이면 바로, 규칙 필터에 걸렸으면 마지막 메시지 30초 뒤, 아니면 10분 뒤.
 * 분석 대상이 아닌 메시지(사진 등)도 대화가 이어지는 것이므로 기다리는 방의 시각을 미룬다</li>
 * <li>{@link #runDue}: 시각이 된 방을 꺼내 요청을 만든다. Redis 에서 먼저 지운 서버만 처리한다 (서버 여러 대)</li>
 * </ul>
 * Redis 상태는 시각 계산용일 뿐이고, 어떤 메시지를 보낼지는 요청을 만들 때 DB 에서 다시 정한다 (ExtractJobService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTriggerService {

    static final String DUE_KEY = "ai:due";
    private static final String COUNT = "count";
    private static final String RULE = "rule";
    private static final Duration STATE_TTL = Duration.ofDays(1);
    private static final int DUE_BATCH = 100;

    private final StringRedisTemplate redis;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupSettingsService settingsService;
    private final ExtractJobService jobService;
    private final AiProperties properties;
    private final Clock clock;

    static String roomKey(long roomId) {
        return "ai:room:" + roomId;
    }

    /** 메시지가 저장된 뒤 (커밋 후) */
    public void onMessagePosted(long groupId, long messageId) {
        if (!properties.enabled()) {
            return;
        }
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getSenderId() == null
                || !settingsService.find(groupId).isAiAnalysisEnabled()) {
            return;
        }
        String key = roomKey(message.getRoomId());
        if (isEligible(groupId, message)) {
            redis.opsForHash().increment(key, COUNT, 1);
            if (RuleFilter.matches(message.getBody())) {
                redis.opsForHash().put(key, RULE, "1");
            }
            redis.expire(key, STATE_TTL);
        } else if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        redis.opsForZSet().add(DUE_KEY, Long.toString(message.getRoomId()), dueAt(key).toEpochMilli());
    }

    /**
     * 분석 시각이 된 방마다 요청을 만든다.
     *
     * @return 만든 요청 수
     */
    public int runDue(Instant now) {
        Set<String> due = redis.opsForZSet().rangeByScore(DUE_KEY, 0, now.toEpochMilli(), 0, DUE_BATCH);
        int created = 0;
        for (String roomId : due == null ? Set.<String>of() : due) {
            Long removed = redis.opsForZSet().remove(DUE_KEY, roomId);
            if (removed == null || removed == 0) {
                continue; // 다른 서버가 가져갔다
            }
            String key = roomKey(Long.parseLong(roomId));
            boolean ruleHit = "1".equals(redis.opsForHash().get(key, RULE));
            redis.delete(key);
            try {
                if (jobService.create(Long.parseLong(roomId), ruleHit ? AiTrigger.RULE : AiTrigger.BATCH)
                        .isPresent()) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.warn("AI 분석 요청을 만들지 못했습니다: roomId={}, error={}", roomId, e.getClass().getSimpleName());
            }
        }
        return created;
    }

    private boolean isEligible(long groupId, Message message) {
        if (message.getType() != MessageType.TEXT || message.isDeleted()) {
            return false;
        }
        return memberRepository.findByGroupIdAndUserId(groupId, message.getSenderId())
                .filter(GroupMember::isActive)
                .filter(member -> !member.isAiOptOut())
                .isPresent();
    }

    private Instant dueAt(String key) {
        Instant now = Instant.now(clock);
        Object count = redis.opsForHash().get(key, COUNT);
        if (count != null && Long.parseLong(count.toString()) >= properties.batchSize()) {
            return now;
        }
        return now.plus("1".equals(redis.opsForHash().get(key, RULE)) ? properties.pause() : properties.batchIdle());
    }
}
