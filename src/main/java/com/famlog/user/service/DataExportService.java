package com.famlog.user.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.metrics.FamlogMetrics;
import com.famlog.common.time.FamlogTime;
import com.famlog.media.storage.MediaStorage;
import com.famlog.user.domain.DataExportRequest;
import com.famlog.user.domain.User;
import com.famlog.user.event.DataExportReady;
import com.famlog.user.repository.DataExportRequestRepository;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내 데이터 내보내기 (사양서 4.1 ACC-02, 8.4, 18장).
 * <ul>
 * <li>요청: 진행 중인 요청이 있으면 그것을 돌려준다. 만든 지 24시간이 안 됐으면 429 (실패한 요청은 세지 않는다)</li>
 * <li>만들기(배치): 대기 요청을 행 잠금(SKIP LOCKED)으로 하나씩 잡아 ZIP 을 임시 파일에 쓰고 S3 에 올린다. 준비되면 푸시와
 * 개인 이벤트(data-export.ready)로 알린다. 실패하면 3번까지 다시 시도한다. 삭제 예정 계정은 만들지 않는다</li>
 * <li>다운로드: READY 상태 조회 때마다 10분짜리 링크를 새로 만든다. 7일이 지나면 파일을 지우고 EXPIRED</li>
 * </ul>
 * 감사 로그에는 건수만 남긴다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    static final int BATCH = 20;
    private static final String CONTENT_TYPE = "application/zip";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TARGET = "DATA_EXPORT";

    private final DataExportRequestRepository repository;
    private final UserRepository userRepository;
    private final DataExportWriter writer;
    private final MediaStorage storage;
    private final ChatRelay relay;
    private final ApplicationEventPublisher events;
    private final AuditLogger auditLogger;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DataExportProperties properties;
    private final JsonMapper jsonMapper;
    private final FamlogMetrics metrics;
    private final Clock clock;

    /** 상태와 (READY 일 때만) 다운로드 링크 */
    public record ExportView(DataExportRequest request, URI downloadUrl, String fileName) {
    }

    @Transactional
    public DataExportRequest request(long userId) {
        User user = userRepository.findByIdForUpdate(userId).filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Optional<DataExportRequest> open = repository.findOpen(user.getId());
        if (open.isPresent()) {
            return open.get();
        }
        Instant now = Instant.now(clock);
        if (repository.existsRecent(userId, now.minus(properties.cooldown()))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "내보내기는 하루에 한 번 요청할 수 있어요. 받은 파일은 7일 동안 다시 "
                    + "내려받을 수 있어요.");
        }
        DataExportRequest request = repository.saveAndFlush(new DataExportRequest(userId, now));
        auditLogger.record(userId, AuditAction.DATA_EXPORT_REQUESTED, TARGET, request.getId(), null, null);
        return request;
    }

    /** 내 요청 하나. 다른 사람의 요청이면 404. READY 면 다운로드 링크를 새로 만들고, 처음이면 감사 로그를 남긴다 */
    @Transactional
    public ExportView get(long userId, long exportId) {
        DataExportRequest request = repository.findByIdAndUserId(exportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Instant now = Instant.now(clock);
        if (!request.isReady() || !request.getExpiresAt().isAfter(now)) {
            return new ExportView(request, null, null);
        }
        String fileName = fileName(request);
        URI url = storage.presignDownload(request.getStorageKey(), properties.downloadUrlTtl(), fileName);
        if (request.markDownloaded(now)) {
            auditLogger.record(userId, AuditAction.DATA_EXPORT_DOWNLOADED, TARGET, exportId, null, null);
        }
        return new ExportView(request, url, fileName);
    }

    /** 최근 요청 (최신순 5개). 다운로드 링크는 상세 조회에서만 */
    @Transactional(readOnly = true)
    public List<DataExportRequest> list(long userId) {
        return repository.findByUserIdOrderByIdDesc(userId, Limit.of(5));
    }

    /**
     * 만들 요청 하나를 잡는다 (REQUESTED, 또는 서버가 멈춰 오래된 PROCESSING). 시도 횟수를 다 쓴 채 멈춘 요청은 FAILED.
     * 여러 서버가 함께 돌아도 한 요청은 한 서버만 잡는다
     */
    public Optional<Long> claimNext() {
        Instant now = Instant.now(clock);
        Timestamp staleBefore = Timestamp.from(now.minus(properties.staleAfter()));
        return transactionTemplate.execute(status -> {
            jdbcTemplate.update("""
                    UPDATE data_export_requests SET status = 'FAILED', finished_at = ?, updated_at = now()
                    WHERE status = 'PROCESSING' AND started_at < ? AND attempts >= ?
                    """, Timestamp.from(now), staleBefore, properties.maxAttempts());
            return jdbcTemplate.queryForList("""
                    UPDATE data_export_requests SET status = 'PROCESSING', attempts = attempts + 1, started_at = ?,
                        updated_at = now()
                    WHERE id = (SELECT id FROM data_export_requests
                                WHERE status = 'REQUESTED' OR (status = 'PROCESSING' AND started_at < ?)
                                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                    RETURNING id
                    """, Long.class, Timestamp.from(now), staleBefore).stream().findFirst();
        });
    }

    /** 잡은 요청의 ZIP 을 만든다. DB 읽기와 S3 업로드는 긴 트랜잭션 밖에서, 상태 변경만 짧게 */
    public void process(long exportId) {
        DataExportRequest request = repository.findById(exportId).orElseThrow();
        long userId = request.getUserId();
        Instant now = Instant.now(clock);
        if (!userRepository.findById(userId).map(User::isActive).orElse(false)) {
            transactionTemplate.executeWithoutResult(status -> {
                DataExportRequest current = repository.findById(exportId).orElseThrow();
                current.fail(now);
                repository.saveAndFlush(current);
            });
            return;
        }
        Instant expiresAt = now.plus(properties.linkTtl());
        String key = "exports/%d/%d-%s.zip".formatted(userId, exportId, UUID.randomUUID());
        Path zip = null;
        try {
            zip = Files.createTempFile("famlog-export-", ".zip");
            Map<String, Integer> counts = writer.write(userId, zip, now, expiresAt);
            storage.putFile(key, zip, CONTENT_TYPE);
            long size = Files.size(zip);
            String countsJson = jsonMapper.writeValueAsString(counts);
            transactionTemplate.executeWithoutResult(status -> {
                DataExportRequest current = repository.findById(exportId).orElseThrow();
                current.ready(key, size, countsJson, now, expiresAt);
                repository.saveAndFlush(current);
                auditLogger.record(userId, AuditAction.DATA_EXPORT_READY, TARGET, exportId, null, counts);
                events.publishEvent(new DataExportReady(userId, exportId));
                relay.toUser(userId, ChatEvent.DATA_EXPORT_READY, Map.of("id", exportId));
            });
            log.info("내보내기 준비: exportId={}, size={}", exportId, size);
        } catch (IOException | RuntimeException e) {
            log.warn("내보내기 실패: exportId={}, error={}", exportId, e.getClass().getSimpleName());
            metrics.batchFailure("data-export");
            transactionTemplate.executeWithoutResult(status -> {
                DataExportRequest current = repository.findById(exportId).orElseThrow();
                current.failed(properties.maxAttempts(), Instant.now(clock));
                repository.saveAndFlush(current);
            });
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    log.warn("내보내기 임시 파일을 지우지 못했습니다: exportId={}", exportId);
                }
            }
        }
    }

    /** 링크가 만료된 요청의 파일을 지운다 */
    public int expireDue() {
        List<Long> ids = repository.findExpiredIds(Instant.now(clock), Limit.of(BATCH));
        ids.forEach(this::discard);
        return ids.size();
    }

    /** 계정 파기: 남은 내보내기 파일을 모두 지운다 */
    @Transactional
    public void discardAll(long userId) {
        repository.findReady(userId).forEach(request -> {
            storage.delete(request.getStorageKey());
            request.expire(Instant.now(clock));
        });
        repository.flush();
    }

    private void discard(long exportId) {
        transactionTemplate.executeWithoutResult(status -> {
            DataExportRequest request = repository.findById(exportId).filter(DataExportRequest::isReady)
                    .orElse(null);
            if (request == null) {
                return;
            }
            storage.delete(request.getStorageKey());
            request.expire(Instant.now(clock));
            repository.saveAndFlush(request);
        });
    }

    private static String fileName(DataExportRequest request) {
        return "famlog-export-%s.zip".formatted(FILE_DATE.format(request.getReadyAt().atZone(FamlogTime.DISPLAY_ZONE)));
    }
}
