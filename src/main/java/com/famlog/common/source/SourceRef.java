package com.famlog.common.source;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 채팅에서 등록한 항목의 원문 메시지. 앱은 이 위치로 채팅을 열고 강조한다 (ARC-06).
 * candidateId 가 있으면 GET /candidates/{id} 의 evidence spans 로 추출 부분을, 없으면 메시지 전체를 강조한다
 */
@Schema(description = "채팅에서 등록한 항목의 원문 메시지 (직접 등록했으면 null)")
public record SourceRef(long messageId, long seq,
        @Schema(description = "이 메시지를 근거로 항목을 등록한 후보 (찾을 수 없으면 null)") Long candidateId) {
}
