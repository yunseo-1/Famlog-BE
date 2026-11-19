package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.AiJob;
import com.famlog.candidate.repository.AiJobRepository;
import com.famlog.group.service.GroupSettingsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 워커 분석 결과를 반영한다 (Redis Streams famlog:ai.extract-results, 필드는 워커 worker/app/ai/schemas.py ExtractResult).
 * ai_jobs 에 상태·측정값을 남기고, 완료 결과의 후보는 CandidateService 로 검증·정규화해 저장한다 (candidate_count = 새로 만든 후보 수).
 * 결과가 왔을 때 그룹 AI 분석이 꺼져 있으면 후보를 저장하지 않는다 (DONE, 0개).
 * 후보 내용은 로그에 남기지 않는다. 이미 끝난 요청의 결과가 다시 오면 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractResultHandler {

    private final AiJobRepository jobRepository;
    private final CandidateService candidateService;
    private final GroupSettingsService settingsService;
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
            JsonNode candidates = candidates(result.get("candidates"));
            if (candidates == null) {
                job.fail("INVALID_RESULT", usage, now);
            } else {
                boolean enabled = settingsService.find(job.getGroupId()).isAiAnalysisEnabled();
                job.complete(usage, enabled ? candidateService.createFrom(job, usage, candidates) : 0, now);
            }
        } else {
            job.fail(result.getOrDefault("errorCode", "UNKNOWN"), usage, now);
        }
        log.info("AI 분석 결과: jobId={}, status={}, candidates={}, errorCode={}", jobId, job.getStatus(),
                job.getCandidateCount(), job.getErrorCode());
    }

    /** 후보 배열. 배열이 아니면 null */
    private JsonNode candidates(String candidates) {
        try {
            JsonNode node = jsonMapper.readTree(candidates == null ? "" : candidates);
            return node.isArray() ? node : null;
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
