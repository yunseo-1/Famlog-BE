package com.famlog.candidate.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.famlog.candidate.domain.CandidateStatus;
import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.domain.DismissReason;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleRequest;
import com.famlog.todo.dto.TodoDtos.CreateTodoRequest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** AI 등록 후보 API (사양서 6.2 ③~⑤, 11.3 ARC-05). fields 형태는 HANDOFF "후보 생성 규칙 (S18)" */
public final class CandidateDtos {

    private CandidateDtos() {
    }

    /** 채팅방 카드(신뢰도 0.75 이상) / 제안함에만(0.5~0.75) */
    public enum Surface {
        CARD,
        INBOX
    }

    /**
     * @param anchorMessageId 카드를 붙일 메시지 (마지막 근거 메시지)
     * @param entityType      승인해서 만든 항목 SCHEDULE(기념일 포함)·TODO·PLACE
     * @param linkedExisting  장소 후보를 이미 공유된 장소에 연결했다 (새로 만들지 않음)
     */
    public record CandidateResponse(long id, long groupId, CandidateType type, CandidateStatus status,
            @Schema(description = "정규화한 값 (유형별 형태는 HANDOFF 후보 생성 규칙)") Map<String, Object> fields,
            @Schema(description = "AI 추정 항목 (\"추정\" 배지)") List<String> inferred,
            @Schema(description = "채워야 등록되는 항목") List<String> missingFields, double confidence,
            Surface surface, Long anchorMessageId, Long anchorSeq,
            @Schema(description = "승인·무시할 때 보낸다") long version, Long decidedBy, Instant decidedAt,
            DismissReason dismissReason, String entityType, Long entityId, boolean linkedExisting,
            Instant expiresAt, Instant createdAt) {
    }

    /** 최신 순 */
    public record CandidateListResponse(List<CandidateResponse> items,
            @Schema(description = "다음 페이지 커서. 없으면 끝") String nextCursor) {
    }

    /** @param text 근거 메시지 본문 (지운 메시지면 null) */
    public record EvidenceResponse(long messageId, long seq, Long senderId, Instant sentAt,
            @Schema(description = "추출한 부분 (원문 강조용)") List<String> spans, String text) {
    }

    public record CandidateDetailResponse(CandidateResponse candidate, List<EvidenceResponse> evidence) {
    }

    /**
     * 승인. 유형에 맞는 값 하나를 보내면 그 값을 최종으로 쓰고(수정 폼), 아무것도 안 보내면 후보 값 그대로 등록한다.
     * 일정·기념일은 일정 등록 요청, 할 일은 할 일 등록 요청과 같은 형태
     */
    public record ApproveRequest(@Schema(description = "조회한 후보의 version") @NotNull Long version,
            @Valid ScheduleRequest schedule, @Valid CreateTodoRequest todo, @Valid ApprovePlace place) {
    }

    /** @param kakaoPlaceId 후보의 options 중 하나, 또는 방금 검색한 장소 */
    public record ApprovePlace(@NotBlank @Size(max = 30) String kakaoPlaceId, @NotNull PlaceCategory category,
            @Schema(description = "한줄평") @Size(max = 100) String oneLineReview) {
    }

    public record DismissRequest(@NotNull Long version, @Schema(description = "선택") DismissReason reason) {
    }

    /** 승인·무시·되돌리기 결과 */
    public record DecisionResponse(CandidateResponse candidate) {
    }

    /** WebSocket candidate.resolved. decidedByName 은 그룹 호칭, 없으면 닉네임 */
    public record CandidateResolved(long groupId, long candidateId, CandidateStatus status, Long decidedBy,
            String decidedByName, String entityType, Long entityId, boolean linkedExisting) {
    }
}
