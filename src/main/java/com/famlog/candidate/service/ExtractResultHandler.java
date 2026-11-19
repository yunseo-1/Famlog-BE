package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.AiJob;
import com.famlog.candidate.repository.AiJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 워커 분석 결과를 반영한다 (Redis Streams famlog:ai.extract-results, 필드는 워커 worker/app/ai/schemas.py ExtractResult).
 * 지금(S16)은 ai_jobs 상태·측정값만 남긴다. 후보 정규화·검증·저장은 S18에서 붙인다.
 * 후보 내용은 로그에 남기지 않는다. 이미 끝난 요청의 결과가 다시 오면 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractResultHandler {

    private final AiJobRepository jobRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public void apply(Map<String, String> result) {
        long jobId;
        try {
            jobId = Long.parseLong(result.get("jobId"));
        } catch (NumberFormatException e) {
            log.warn("AI 분석 결과 형식이 잘못됐습니다: 필드 jobId");
            return;
        }
        AiJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            log.warn("분석 결과의 요청이 없습니다: jobId={}", jobId);
            return;
        }
        if (job.isFinished()) {
            return;
        }
        Instant now = Instant.now(clock);
        AiJob.Usage usage = new AiJob.Usage(result.get("modelVersion"), result.get("promptVersion"),
                intOrNull(result, "inputTokens"), intOrNull(result, "outputTokens"), intOrNull(result, "latencyMs"));
        if ("DONE".equals(result.get("status"))) {
            Integer count = candidateCount(result.get("candidates"));
            if (count == null) {
                job.fail("INVALID_RESULT", usage, now);
            } else {
                job.complete(usage, count, now);
            }
        } else {
            job.fail(result.getOrDefault("errorCode", "UNKNOWN"), usage, now);
        }
        log.info("AI 분석 결과: jobId={}, status={}, candidates={}, errorCode={}", jobId, job.getStatus(),
                job.getCandidateCount(), job.getErrorCode());
    }

    private Integer candidateCount(String candidates) {
        try {
            JsonNode node = jsonMapper.readTree(candidates == null ? "" : candidates);
            return node.isArray() ? node.size() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Integer intOrNull(Map<String, String> result, String key) {
        try {
            String value = result.get(key);
            return value == null || value.isBlank() ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
