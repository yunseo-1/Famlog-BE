package com.famlog.media.domain;

/**
 * 업로드·처리 상태.
 * PENDING_UPLOAD(URL 발급) → UPLOADED(완료 확인, 워커 대기) → READY(썸네일·GPS 제거 끝) / FAILED(처리 실패) / REJECTED(형식 거부).
 * 원본은 READY가 되어야 내려받을 수 있다 (그 전에는 GPS가 남아 있을 수 있다).
 */
public enum MediaStatus {
    PENDING_UPLOAD,
    UPLOADED,
    READY,
    FAILED,
    REJECTED;

    /** 메시지에 붙여 보낼 수 있는 상태 (처리 중이어도 보내고, 썸네일은 media.updated 로 채운다) */
    public boolean isSendable() {
        return this == UPLOADED || this == READY;
    }
}
