package com.famlog.candidate.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.candidate.dto.CandidateDtos.CandidateResponse;
import com.famlog.candidate.dto.CandidateDtos.Surface;
import com.famlog.candidate.repository.CandidateEvidenceWriter.Anchor;
import com.famlog.common.source.ItemSources;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 후보 → 응답. 신뢰도 0.75 이상은 채팅방 카드, 그 아래는 제안함 (사양서 6.2 ②) */
@Component
@RequiredArgsConstructor
public class CandidateMapper {

    public static final double CARD_CONFIDENCE = 0.75;
    static final String PLACE_LINKED = "PLACE_LINKED";
    /** target_ref 안의 승인 기록 (바꾼 뒤 일정 version). 응답에는 내보내지 않는다 */
    static final String APPLIED = "applied";

    private final JsonMapper jsonMapper;

    public CandidateResponse toResponse(RegistrationCandidate c, Anchor anchor) {
        boolean linked = PLACE_LINKED.equals(c.getCreatedEntityType());
        return new CandidateResponse(c.getId(), c.getGroupId(), c.getType(), c.getStatus(), fields(c),
                List.of(c.getInferredFields()), List.of(c.getMissingFields()), c.getConfidence(),
                c.getConfidence() >= CARD_CONFIDENCE ? Surface.CARD : Surface.INBOX,
                anchor == null ? null : anchor.messageId(), anchor == null ? null : anchor.seq(), c.getVersion(),
                c.getDecidedBy(), c.getDecidedAt(), c.getDismissReason(),
                linked ? ItemSources.PLACE : c.getCreatedEntityType(), c.getCreatedEntityId(), linked, target(c),
                c.getExpiresAt(), c.getCreatedAt());
    }

    /** target_ref 에서 되돌리기용 기록(applied)을 뺀 것 */
    public Map<String, Object> target(RegistrationCandidate c) {
        if (!c.hasTarget()) {
            return null;
        }
        Map<String, Object> target = jsonMapper.readValue(c.getTargetRef(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        target.remove(APPLIED);
        return target;
    }

    public Map<String, Object> fields(RegistrationCandidate c) {
        return jsonMapper.readValue(c.getFields(), new TypeReference<Map<String, Object>>() {
        });
    }
}
