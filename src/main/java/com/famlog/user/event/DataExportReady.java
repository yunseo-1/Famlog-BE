package com.famlog.user.event;

/** 내 데이터 내보내기 ZIP 이 준비됐다 (사양서 4.1 ACC-02 "준비되면 알림"). NOTI 가 커밋 뒤 그 사용자의 기기로 알린다 */
public record DataExportReady(long userId, long exportId) {
}
