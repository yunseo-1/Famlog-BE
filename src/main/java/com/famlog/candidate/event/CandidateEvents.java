package com.famlog.candidate.event;

/** 후보 처리 이벤트 (같은 트랜잭션에서 채팅 시스템 메시지를 올리거나 지운다) */
public final class CandidateEvents {

    private CandidateEvents() {
    }

    /**
     * 승인: "엄마님이 '할머니 댁 방문' 일정을 캘린더에 등록했어요."
     *
     * @param what 이름 뒤에 붙는 문장 ("님이 '할머니 댁 방문' 일정을 캘린더에 등록했어요.")
     */
    public record CandidateApproved(long groupId, long candidateId, long userId, String what, String entityType,
            long entityId) {
    }

    /** 되돌리기: 승인 시스템 메시지를 지운다 */
    public record CandidateUndone(long groupId, long candidateId) {
    }
}
