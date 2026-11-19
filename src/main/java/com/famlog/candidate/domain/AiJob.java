package com.famlog.candidate.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워커에 보낸 AI 분석 요청 (LLM 호출 기록, 사양서 6.5 AR-10, 8.7).
 * 프롬프트·응답 원문은 저장하지 않는다. 모델·프롬프트 버전, 토큰 수, 지연 시간, 결과 상태만 남긴다.
 */
@Getter
@Entity
@Table(name = "ai_jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJob extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(name = "from_seq", nullable = false, updatable = false)
    private long fromSeq;

    @Column(name = "to_seq", nullable = false, updatable = false)
    private long toSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AiTrigger trigger;

    @Column(name = "message_count", nullable = false, updatable = false)
    private int messageCount;

    /** 임시 ID → 사용자 ID {"M1": 12} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "member_refs", nullable = false, updatable = false)
    private String memberRefs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiJobStatus status;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "candidate_count")
    private Integer candidateCount;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public AiJob(long groupId, long roomId, long fromSeq, long toSeq, AiTrigger trigger, int messageCount,
            String memberRefs) {
        this.groupId = groupId;
        this.roomId = roomId;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.trigger = trigger;
        this.messageCount = messageCount;
        this.memberRefs = memberRefs;
        this.status = AiJobStatus.QUEUED;
    }

    /** 워커가 알려 준 측정값 (원문 없음) */
    public record Usage(String modelVersion, String promptVersion, Integer inputTokens, Integer outputTokens,
            Integer latencyMs) {
    }

    public void complete(Usage usage, int candidateCount, Instant now) {
        apply(usage, now);
        this.status = AiJobStatus.DONE;
        this.candidateCount = candidateCount;
    }

    public void fail(String errorCode, Usage usage, Instant now) {
        apply(usage, now);
        this.status = AiJobStatus.FAILED;
        this.errorCode = errorCode;
    }

    public boolean isFinished() {
        return status != AiJobStatus.QUEUED;
    }

    private void apply(Usage usage, Instant now) {
        this.modelVersion = usage.modelVersion();
        this.promptVersion = usage.promptVersion();
        this.inputTokens = usage.inputTokens();
        this.outputTokens = usage.outputTokens();
        this.latencyMs = usage.latencyMs();
        this.finishedAt = now;
    }
}
