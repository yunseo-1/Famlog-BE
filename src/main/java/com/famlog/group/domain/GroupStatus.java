package com.famlog.group.domain;

public enum GroupStatus {
    ACTIVE,
    /** 삭제 예정 (30일 유예). 이때부터 구성원도 접근할 수 없다 */
    PENDING_DELETION,
    /** 파기됨: 그룹 데이터는 모두 지웠고 신고·감사 로그가 가리키는 행만 남는다 (이름·사진 없음) */
    DELETED
}
