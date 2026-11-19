package com.famlog.candidate.controller;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.candidate.domain.CandidateStatus;
import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.dto.CandidateDtos.ApproveRequest;
import com.famlog.candidate.dto.CandidateDtos.CandidateDetailResponse;
import com.famlog.candidate.dto.CandidateDtos.CandidateListResponse;
import com.famlog.candidate.dto.CandidateDtos.DecisionResponse;
import com.famlog.candidate.dto.CandidateDtos.DismissRequest;
import com.famlog.candidate.service.CandidateDecisionService;
import com.famlog.candidate.service.CandidateQueryService;
import com.famlog.common.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * AI 등록 후보 (사양서 6장, 11.3 ARC-05). /groups/{groupId}/** 는 GroupPathAccessFilter 가 구성원 여부를 먼저 확인하고,
 * /candidates/{id} 는 서비스가 후보의 그룹으로 확인한다 (다른 그룹이면 404).
 */
@RestController
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateQueryService queryService;
    private final CandidateDecisionService decisionService;

    @Operation(summary = "후보 목록", description = "최신 순 커서 페이징. status 기본 PENDING(만료 제외), 여러 개 가능. "
            + "채팅 화면은 status=PENDING,APPROVED 와 보이는 메시지의 seq 범위(fromSeq~toSeq, 카드를 붙일 메시지 기준)로 부른다. "
            + "surface: CARD(신뢰도 0.75 이상, 채팅방 카드) / INBOX(제안함에만)")
    @GetMapping("/api/v1/groups/{groupId}/candidates")
    CandidateListResponse listCandidates(AuthUser user, @PathVariable long groupId,
            @RequestParam(required = false) Set<CandidateStatus> status,
            @RequestParam(required = false) CandidateType type,
            @Parameter(description = "카드를 붙일 메시지 seq 이상") @RequestParam(required = false) Long fromSeq,
            @Parameter(description = "카드를 붙일 메시지 seq 이하") @RequestParam(required = false) Long toSeq,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {
        return queryService.list(groupId, user.userId(), status, type, fromSeq, toSeq, cursor, size);
    }

    @Operation(summary = "후보 하나", description = "근거 메시지(본문·보낸 사람·시각·추출 부분) 포함. 다른 그룹 후보면 404")
    @GetMapping("/api/v1/candidates/{candidateId}")
    CandidateDetailResponse getCandidate(AuthUser user, @PathVariable long candidateId) {
        return queryService.get(candidateId, user.userId());
    }

    @Operation(summary = "후보 승인 (등록)", description = "값을 안 보내면 후보 값 그대로 등록. 채울 항목이 있거나 골라야 하면 "
            + "422 CANDIDATE_MISSING_FIELDS (fieldErrors 에 항목) → 수정 폼에서 유형에 맞는 값(schedule·todo·place) 하나를 보낸다. "
            + "다른 가족이 먼저 처리했으면 409 CANDIDATE_ALREADY_DECIDED, 만료면 409 CANDIDATE_EXPIRED. "
            + "같은 사람이 다시 보내면 같은 결과")
    @PostMapping("/api/v1/candidates/{candidateId}/approve")
    DecisionResponse approveCandidate(AuthUser user, @PathVariable long candidateId,
            @Valid @RequestBody ApproveRequest request) {
        return decisionService.approve(candidateId, user.userId(), request);
    }

    @Operation(summary = "후보 무시", description = "사유는 선택 (ALREADY_REGISTERED·JOKE·WRONG). 무시한 내용은 다시 제안하지 않는다")
    @PostMapping("/api/v1/candidates/{candidateId}/dismiss")
    DecisionResponse dismissCandidate(AuthUser user, @PathVariable long candidateId,
            @Valid @RequestBody DismissRequest request) {
        return decisionService.dismiss(candidateId, user.userId(), request);
    }

    @Operation(summary = "승인 되돌리기", description = "승인한 가족이나 ADMIN 이상이 5분 안에 (아니면 403 CANDIDATE_NOT_UNDOABLE). "
            + "만든 항목과 승인 시스템 메시지를 지운다")
    @PostMapping("/api/v1/candidates/{candidateId}/undo")
    DecisionResponse undoCandidate(AuthUser user, @PathVariable long candidateId) {
        return decisionService.undo(candidateId, user.userId());
    }
}
