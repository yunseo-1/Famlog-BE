package com.famlog.candidate.dto;

import java.util.List;

import com.famlog.candidate.domain.CandidateType;
import com.famlog.group.domain.FamilyRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 워커와 주고받는 AI 분석 형식 (워커 worker/app/ai/schemas.py 와 같다).
 * <ul>
 * <li>요청 본문: Redis Streams famlog:ai.extract 의 payload 필드. 사람 이름·사용자 ID 대신 임시 ID(M1, M2…)와 역할·호칭만 담고,
 * 본문은 마스킹한 뒤 담는다 (사양서 8.7)</li>
 * <li>결과의 후보: famlog:ai.extract-results 의 candidates 필드</li>
 * </ul>
 */
public final class ExtractDtos {

    private ExtractDtos() {
    }

    /**
     * @param timezone 발송 시각을 해석하는 기준 (Asia/Seoul)
     * @param members  그룹 구성원 (분석 제외한 사람도 담당자·참석자가 될 수 있어 넣는다)
     * @param context  분석 구간 앞 맥락 (최대 20개). 여기서는 후보를 만들지 않는다
     * @param messages 분석 구간
     */
    public record ExtractPayload(String timezone, List<MemberRef> members, List<ExtractMessage> context,
            List<ExtractMessage> messages) {
    }

    /** @param ref 임시 ID (M1, M2…). 요청마다 새로 매긴다 */
    public record MemberRef(String ref, FamilyRole familyRole, String familyTitle) {
    }

    /**
     * @param id     메시지 id (근거 연결용. 개인정보 아님)
     * @param sender 보낸 사람 임시 ID
     * @param sentAt 발송 시각 (+09:00 ISO-8601). 상대 날짜 해석 기준
     * @param text   마스킹한 본문
     */
    public record ExtractMessage(long id, String sender, String sentAt, String text) {
    }

    /**
     * LLM 이 낸 후보 종류. SCHEDULE_CHANGE·TODO_DONE 은 기존 일정 변경·할 일 완료 표현이다 (S19-2).
     * 서버가 그룹의 기존 항목에서 대상을 찾아 SCHEDULE·TODO 후보(target_ref)로 만든다. 못 찾으면 만들지 않는다
     */
    public enum ExtractedKind {
        SCHEDULE(CandidateType.SCHEDULE),
        ANNIVERSARY(CandidateType.ANNIVERSARY),
        TODO(CandidateType.TODO),
        PLACE(CandidateType.PLACE),
        SCHEDULE_CHANGE(CandidateType.SCHEDULE),
        TODO_DONE(CandidateType.TODO);

        private final CandidateType candidateType;

        ExtractedKind(CandidateType candidateType) {
            this.candidateType = candidateType;
        }

        public CandidateType candidateType() {
            return candidateType;
        }
    }

    /**
     * 워커 결과(famlog:ai.extract-results 의 candidates 필드)의 후보 하나. 워커가 스키마를 검사했지만 여기서 한 번 더 읽는다.
     * 값은 모두 원문 표현이다. 날짜 계산·구성원 매칭·장소 확정은 서버가 한다 (CandidateBuilder).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedCandidate(ExtractedKind type, ExtractedFields fields, double confidence,
            List<ExtractedEvidence> evidence) {
    }

    /**
     * 유형별 원문 표현 (워커 worker/app/ai/schemas.py 와 같다). 유형에 없는 값은 null.
     * <ul>
     * <li>SCHEDULE: title, dateText, startTimeText, endTimeText, locationText, attendeeTexts</li>
     * <li>TODO: title, dueText, assigneeTexts</li>
     * <li>ANNIVERSARY: title, dateText, personText</li>
     * <li>PLACE: name, regionHint, opinion</li>
     * <li>SCHEDULE_CHANGE: title(어떤 일정인지 핵심어), fromDateText·fromTimeText(바꾸기 전), dateText·startTimeText(바꿀 값)</li>
     * <li>TODO_DONE: title(끝낸 일의 핵심어)</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedFields(String title, String dateText, String startTimeText, String endTimeText,
            String locationText, List<String> attendeeTexts, String dueText, List<String> assigneeTexts,
            String personText, String name, String regionHint, String opinion, String fromDateText,
            String fromTimeText) {
    }

    /** @param spans 추출한 부분 (원문 강조용) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedEvidence(long messageId, List<String> spans) {
    }
}
