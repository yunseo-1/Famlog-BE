package com.famlog.candidate.dto;

import java.util.List;

import com.famlog.group.domain.FamilyRole;

/**
 * 워커에 보내는 분석 요청 본문 (Redis Streams famlog:ai.extract 의 payload 필드, 워커 worker/app/ai/schemas.py 와 같다).
 * 사람 이름·사용자 ID 대신 임시 ID(M1, M2…)와 역할·호칭만 담고, 본문은 마스킹한 뒤 담는다 (사양서 8.7).
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
}
