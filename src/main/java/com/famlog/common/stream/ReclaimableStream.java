package com.famlog.common.stream;

/** 멈춘 대기 메시지를 회수할 결과 스트림 소비자. {@link StreamReclaimTicker} 가 1분마다 부른다 */
public interface ReclaimableStream {

    /** @return 다시 처리했거나 DLQ 로 옮긴 개수 */
    int reclaimPending();
}
