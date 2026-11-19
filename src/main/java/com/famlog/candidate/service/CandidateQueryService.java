package com.famlog.candidate.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.CandidateStatus;
import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.candidate.dto.CandidateDtos.CandidateDetailResponse;
import com.famlog.candidate.dto.CandidateDtos.CandidateListResponse;
import com.famlog.candidate.dto.CandidateDtos.CandidateResponse;
import com.famlog.candidate.dto.CandidateDtos.EvidenceResponse;
import com.famlog.candidate.repository.CandidateEvidenceWriter;
import com.famlog.candidate.repository.CandidateEvidenceWriter.Anchor;
import com.famlog.candidate.repository.RegistrationCandidateRepository;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.service.GroupAccessChecker;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 후보 목록·상세 (사양서 11.3 ARC-05, SCR-14 제안함, 6.2 ③ 근거 표시).
 * <ul>
 * <li>목록은 최신 순 커서 페이징. 만료된 PENDING 은 빼고, fromSeq·toSeq 로 카드를 붙일 메시지 범위를 고를 수 있다 (채팅 화면)</li>
 * <li>상세는 근거 메시지 본문·보낸 사람·시각·추출 부분 (지운 메시지는 본문 null)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CandidateQueryService {

    static final int DEFAULT_PAGE_SIZE = 30;

    private final RegistrationCandidateRepository candidateRepository;
    private final CandidateEvidenceWriter evidence;
    private final CandidateMapper mapper;
    private final GroupAccessChecker accessChecker;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CandidateListResponse list(long groupId, long userId, Set<CandidateStatus> statuses, CandidateType type,
            Long fromSeq, Long toSeq, String cursor, Integer size) {
        accessChecker.requireMember(groupId, userId);
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        long before = parseCursor(cursor);
        Set<CandidateStatus> wanted = statuses == null || statuses.isEmpty() ? Set.of(CandidateStatus.PENDING)
                : statuses;
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT c.id
                FROM registration_candidates c
                JOIN LATERAL (SELECT max(m.seq) AS seq FROM candidate_evidence e JOIN messages m ON m.id = e.message_id
                              WHERE e.candidate_id = c.id) a ON true
                WHERE c.group_id = ? AND c.status = ANY (?) AND (?::varchar IS NULL OR c.type = ?)
                  AND NOT (c.status = 'PENDING' AND c.expires_at <= ?)
                  AND (?::bigint IS NULL OR a.seq >= ?) AND (?::bigint IS NULL OR a.seq <= ?)
                  AND c.id < ?
                ORDER BY c.id DESC
                LIMIT ?
                """, Long.class, groupId, wanted.stream().map(Enum::name).toArray(String[]::new),
                type == null ? null : type.name(), type == null ? null : type.name(),
                Timestamp.from(Instant.now(clock)), fromSeq, fromSeq, toSeq, toSeq, before, pageSize + 1);
        boolean hasNext = ids.size() > pageSize;
        List<Long> page = hasNext ? ids.subList(0, pageSize) : ids;
        Map<Long, RegistrationCandidate> found = candidateRepository.findAllById(page).stream()
                .collect(Collectors.toMap(RegistrationCandidate::getId, Function.identity()));
        Map<Long, Anchor> anchors = evidence.anchors(page);
        List<CandidateResponse> items = new ArrayList<>();
        for (long id : page) {
            items.add(mapper.toResponse(found.get(id), anchors.get(id)));
        }
        return new CandidateListResponse(items, hasNext ? Long.toString(page.getLast()) : null);
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse get(long candidateId, long userId) {
        RegistrationCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.requireMemberOrNotFound(candidate.getGroupId(), userId);
        List<EvidenceResponse> rows = evidence.evidence(candidateId).stream()
                .map(row -> new EvidenceResponse(row.messageId(), row.seq(), row.senderId(), row.sentAt(),
                        jsonMapper.readValue(row.spansJson(), new TypeReference<List<String>>() {
                        }), row.body()))
                .toList();
        Anchor anchor = rows.isEmpty() ? null
                : new Anchor(rows.getLast().messageId(), rows.getLast().seq());
        return new CandidateDetailResponse(mapper.toResponse(candidate, anchor), rows);
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "목록 위치(cursor)가 올바르지 않아요.");
        }
    }
}
