package com.famlog.candidate.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.AiJob;
import com.famlog.candidate.domain.AiTrigger;
import com.famlog.candidate.dto.ExtractDtos.ExtractMessage;
import com.famlog.candidate.dto.ExtractDtos.ExtractPayload;
import com.famlog.candidate.dto.ExtractDtos.MemberRef;
import com.famlog.candidate.repository.AiJobRepository;
import com.famlog.candidate.repository.AiSourceReader;
import com.famlog.candidate.repository.AiSourceReader.Member;
import com.famlog.candidate.repository.AiSourceReader.Room;
import com.famlog.candidate.repository.AiSourceReader.SourceMessage;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.GroupSettings;
import com.famlog.group.service.GroupSettingsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 방의 분석 안 된 구간으로 분석 요청을 만든다 (사양서 6.2 ①, 8.7).
 * <ul>
 * <li>구간은 (ai_analyzed_seq, last_seq]. 만들든 안 만들든 기준점을 끝까지 옮긴다 (같은 메시지를 두 번 보내지 않는다)</li>
 * <li>그룹 AI 분석이 꺼졌거나 분석 대상 메시지가 없으면 요청을 만들지 않는다 (AR-10, SET-01)</li>
 * <li>구성원은 임시 ID(M1…)와 역할·호칭만, 본문은 마스킹해서 보낸다. 임시 ID 대응은 ai_jobs 에 남긴다</li>
 * </ul>
 * 본문은 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractJobService {

    private final AiSourceReader sourceReader;
    private final AiJobRepository jobRepository;
    private final GroupSettingsService settingsService;
    private final ExtractJobPublisher publisher;
    private final AiProperties properties;
    private final JsonMapper jsonMapper;

    @Transactional
    public Optional<AiJob> create(long roomId, AiTrigger trigger) {
        Room room = sourceReader.room(roomId).orElse(null);
        if (room == null || room.lastSeq() <= room.analyzedSeq()) {
            return Optional.empty();
        }
        long fromSeq = room.analyzedSeq();
        long toSeq = room.lastSeq();
        GroupSettings settings = settingsService.find(room.groupId());
        List<SourceMessage> messages = properties.enabled() && settings.isAiAnalysisEnabled()
                ? sourceReader.eligibleMessages(room, fromSeq, toSeq, settings.getAiEnabledAt(),
                        properties.maxMessages())
                : List.of();
        if (!sourceReader.advance(roomId, fromSeq, toSeq) || messages.isEmpty()) {
            return Optional.empty();
        }
        List<SourceMessage> context = sourceReader.eligibleMessages(room, 0, fromSeq, settings.getAiEnabledAt(),
                properties.contextSize());

        List<Member> members = sourceReader.members(room.groupId());
        Map<Long, String> refs = new LinkedHashMap<>();
        members.forEach(member -> refs.put(member.userId(), "M" + (refs.size() + 1)));
        ExtractPayload payload = new ExtractPayload(FamlogTime.DISPLAY_ZONE.getId(),
                members.stream().map(m -> new MemberRef(refs.get(m.userId()), m.familyRole(), m.familyTitle()))
                        .toList(),
                toExtract(context, refs), toExtract(messages, refs));
        Map<String, Long> memberRefs = refs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a, b) -> a, LinkedHashMap::new));

        AiJob job = jobRepository.save(new AiJob(room.groupId(), roomId, fromSeq, toSeq, trigger, messages.size(),
                jsonMapper.writeValueAsString(memberRefs)));
        publisher.publish(job.getId(), jsonMapper.writeValueAsString(payload));
        log.info("AI 분석 요청: jobId={}, groupId={}, trigger={}, messages={}, context={}", job.getId(),
                room.groupId(), trigger, messages.size(), context.size());
        return Optional.of(job);
    }

    private static List<ExtractMessage> toExtract(List<SourceMessage> messages, Map<Long, String> refs) {
        Function<SourceMessage, ExtractMessage> convert = m -> new ExtractMessage(m.id(), refs.get(m.senderId()),
                m.sentAt().atZone(FamlogTime.DISPLAY_ZONE).toOffsetDateTime().toString(), PiiMasker.mask(m.body()));
        return messages.stream().map(convert).toList();
    }
}
