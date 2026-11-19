package com.famlog.common.source;

import io.swagger.v3.oas.annotations.media.Schema;

/** 채팅에서 등록한 항목의 원문 메시지. 앱은 이 위치로 채팅을 열고 강조한다 (ARC-06) */
@Schema(description = "채팅에서 등록한 항목의 원문 메시지 (직접 등록했으면 null)")
public record SourceRef(long messageId, long seq) {
}
