package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.AiJob;
import com.famlog.candidate.domain.CandidateStatus;
import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.candidate.dto.ExtractDtos.ExtractedCandidate;
import com.famlog.candidate.dto.ExtractDtos.ExtractedEvidence;
import com.famlog.candidate.normalize.MemberMatcher;
import com.famlog.candidate.repository.AiSourceReader;
import com.famlog.candidate.repository.CandidateEvidenceWriter;
import com.famlog.candidate.repository.CandidateEvidenceWriter.EvidenceMessage;
import com.famlog.candidate.repository.RegistrationCandidateRepository;
import com.famlog.candidate.service.CandidateBuilder.Built;
import com.famlog.place.repository.PlaceAliasRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 워커 결과의 후보를 검증·정규화해 registration_candidates(PENDING)에 저장한다 (사양서 6.2 ②, 6.4).
 * 실제 일정·할 일·장소는 만들지 않는다 (승인 API, S19).
 * <ul>
 * <li>근거 메시지가 이 요청의 분석 구간(같은 방, (from_seq, to_seq])에 있고 지워지지 않았어야 한다. 아니면 그 후보를 버린다</li>
 * <li>중복: 같은 dedupKey 의 가장 최근 후보가 PENDING(만료 전)·APPROVED 면 근거만 붙이고, DISMISSED 면 만들지 않는다.
 * EXPIRED·UNDONE·만료된 PENDING 이면 새로 만든다</li>
 * <li>같은 그룹의 저장은 한 번에 하나씩 (DB advisory lock)</li>
 * </ul>
 * 후보 내용은 로그에 남기지 않는다 (개수만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateBuilder builder;
    private final RegistrationCandidateRepository candidateRepository;
    private final CandidateEvidenceWriter evidenceWriter;
    private final AiSourceReader sourceReader;
    private final PlaceAliasRepository aliasRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * @param usage 이 결과를 낸 모델·프롬프트 버전 (후보에 남긴다)
     * @return 새로 만든 후보 수
     */
    @Transactional
    public int createFrom(AiJob job, AiJob.Usage usage, JsonNode candidates) {
        List<ExtractedCandidate> extracted = new ArrayList<>();
        int skipped = 0;
        for (JsonNode node : candidates) {
            try {
                extracted.add(jsonMapper.treeToValue(node, ExtractedCandidate.class));
            } catch (JacksonException e) {
                skipped++;
            }
        }
        Map<Long, EvidenceMessage> messages = evidenceWriter.messages(extracted.stream()
                .flatMap(c -> c.evidence() == null ? Stream.empty() : c.evidence().stream())
                .map(ExtractedEvidence::messageId).distinct().toList())
                .stream().collect(Collectors.toMap(EvidenceMessage::id, Function.identity()));
        CandidateBuilder.Context context = new CandidateBuilder.Context(
                sourceReader.members(job.getGroupId()).stream()
                        .map(m -> new MemberMatcher.Member(m.userId(), m.familyRole(), m.familyTitle())).toList(),
                aliasRepository.findByGroupId(job.getGroupId()), Instant.now(clock));

        evidenceWriter.lockGroup(job.getGroupId());
        int created = 0;
        int merged = 0;
        for (ExtractedCandidate candidate : extracted) {
            List<EvidenceMessage> evidence = validEvidence(job, candidate, messages);
            Optional<Built> built = evidence.isEmpty() ? Optional.empty()
                    : builder.build(candidate, CandidateBuilder.anchorOf(candidate, evidence), context);
            if (built.isEmpty()) {
                skipped++;
                continue;
            }
            switch (save(job, usage, built.get(), candidate.evidence(), context.now())) {
                case CREATED -> created++;
                case MERGED -> merged++;
                case SKIPPED -> skipped++;
            }
        }
        log.info("후보 생성: jobId={}, created={}, merged={}, skipped={}", job.getId(), created, merged, skipped);
        return created;
    }

    private enum Outcome {
        CREATED, MERGED, SKIPPED
    }

    private Outcome save(AiJob job, AiJob.Usage usage, Built built, List<ExtractedEvidence> evidence, Instant now) {
        Optional<RegistrationCandidate> latest = candidateRepository
                .findFirstByGroupIdAndDedupKeyOrderByIdDesc(job.getGroupId(), built.dedupKey());
        if (latest.isPresent()) {
            RegistrationCandidate existing = latest.get();
            if (existing.getStatus() == CandidateStatus.DISMISSED) {
                return Outcome.SKIPPED;
            }
            if (existing.getStatus() == CandidateStatus.APPROVED || existing.isOpen(now)) {
                addEvidence(existing.getId(), evidence);
                return Outcome.MERGED;
            }
        }
        RegistrationCandidate candidate = candidateRepository.saveAndFlush(new RegistrationCandidate(job,
                usage, built.type(), jsonMapper.writeValueAsString(built.fields()), built.inferred(), built.missing(),
                built.confidence(), built.dedupKey(), built.expiresAt()));
        addEvidence(candidate.getId(), evidence);
        return Outcome.CREATED;
    }

    private void addEvidence(long candidateId, List<ExtractedEvidence> evidence) {
        for (ExtractedEvidence e : evidence) {
            evidenceWriter.add(candidateId, e.messageId(),
                    jsonMapper.writeValueAsString(e.spans() == null ? List.of() : e.spans()));
        }
    }

    /** 근거가 모두 이 요청의 분석 구간에 있으면 seq 순으로, 하나라도 아니면 빈 목록 */
    private static List<EvidenceMessage> validEvidence(AiJob job, ExtractedCandidate candidate,
            Map<Long, EvidenceMessage> messages) {
        if (candidate.evidence() == null || candidate.evidence().isEmpty()) {
            return List.of();
        }
        List<EvidenceMessage> valid = new ArrayList<>();
        for (ExtractedEvidence e : candidate.evidence()) {
            EvidenceMessage message = messages.get(e.messageId());
            if (message == null || message.roomId() != job.getRoomId() || message.seq() <= job.getFromSeq()
                    || message.seq() > job.getToSeq() || message.body() == null) {
                return List.of();
            }
            valid.add(message);
        }
        valid.sort(Comparator.comparingLong(EvidenceMessage::seq));
        return valid;
    }
}
