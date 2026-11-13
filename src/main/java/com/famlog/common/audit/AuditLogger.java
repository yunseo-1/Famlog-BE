package com.famlog.common.audit;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 로그 (사양서 9.8, 1년 보관): 권한 변경, 구성원 내보내기, 그룹 삭제, 데이터 내보내기, 계정 삭제, 관리자의 신고 콘텐츠 열람.
 * 호출한 트랜잭션에 함께 기록된다 (동작이 롤백되면 기록도 남지 않는다). meta 에 개인정보·메시지 본문을 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public void record(long actorId, AuditAction action, String targetType, Long targetId, Long groupId,
            Map<String, ?> meta) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (actor_id, action, target_type, target_id, group_id, meta, ip)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, actorId, action.name(), targetType, targetId, groupId,
                meta == null || meta.isEmpty() ? null : jsonMapper.writeValueAsString(meta), clientIp());
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
