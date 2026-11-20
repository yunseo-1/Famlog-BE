package com.famlog.report.service;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.candidate.repository.RegistrationCandidateRepository;
import com.famlog.chat.service.ChatService;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.media.service.MediaService;
import com.famlog.report.domain.Report;
import com.famlog.report.domain.ReportAction;
import com.famlog.report.domain.ReportReason;
import com.famlog.report.domain.ReportStatus;
import com.famlog.report.domain.ReportTargetType;
import com.famlog.report.dto.ReportDtos.CreateReportRequest;
import com.famlog.report.dto.ReportDtos.ReportResponse;
import com.famlog.report.repository.ReportRepository;

import lombok.RequiredArgsConstructor;

/**
 * 신고 접수 (사양서 4.2 MOD-01, Google Play UGC·AI 생성물 정책). 앱을 나가지 않고 메시지·사진·AI 후보·구성원을 신고한다.
 * <ul>
 * <li>대상은 내가 볼 수 있는 것만: 다른 그룹(또는 들어오기 전) 대상은 404, 구성원이 아닌 그룹은 403 (사양서 9.3)</li>
 * <li>그룹은 대상에서 정한다. 구성원 신고(USER)만 groupId 를 받고, 나와 상대가 그 그룹 구성원이어야 한다(나간 사람 포함)</li>
 * <li>같은 사람이 같은 대상을 처리 전에 다시 신고하면 그 신고를 그대로 돌려준다</li>
 * <li>신고 내용(상세)은 로그에 남기지 않는다. 처리는 관리자 도구 (admin)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final ChatService chatService;
    private final MediaService mediaService;
    private final RegistrationCandidateRepository candidateRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;

    @Transactional
    public ReportResponse create(long userId, CreateReportRequest request) {
        String detail = request.detail() == null || request.detail().isBlank() ? null : request.detail().strip();
        if (request.reason() == ReportReason.OTHER && detail == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기타 사유는 내용을 적어 주세요.");
        }
        long groupId = groupOf(userId, request);
        Optional<Report> open = reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndStatus(userId,
                request.targetType(), request.targetId(), ReportStatus.OPEN);
        if (open.isPresent()) {
            return ReportResponse.from(open.get());
        }
        return ReportResponse.from(reportRepository.saveAndFlush(new Report(userId, groupId, request.targetType(),
                request.targetId(), request.reason(), detail)));
    }

    private long groupOf(long userId, CreateReportRequest request) {
        long targetId = request.targetId();
        return switch (request.targetType()) {
            case MESSAGE -> chatService.groupOfReportableMessage(targetId, userId);
            case MEDIA -> mediaService.groupOfReportableMedia(targetId, userId);
            case AI_OUTPUT -> {
                RegistrationCandidate candidate = candidateRepository.findById(targetId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                accessChecker.requireMemberOrNotFound(candidate.getGroupId(), userId);
                yield candidate.getGroupId();
            }
            case USER -> {
                if (request.groupId() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "구성원 신고는 그룹을 알려 주세요.");
                }
                if (Objects.equals(targetId, userId)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "나를 신고할 수는 없어요.");
                }
                accessChecker.requireMember(request.groupId(), userId);
                memberRepository.findByGroupIdAndUserId(request.groupId(), targetId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                yield request.groupId();
            }
        };
    }

    /** 신고 대상 종류에 맞는 조치인가 (관리자 도구가 쓴다) */
    public static boolean actionFits(ReportTargetType type, ReportAction action) {
        return switch (action) {
            case HIDE_MESSAGE -> type == ReportTargetType.MESSAGE || type == ReportTargetType.MEDIA;
            case DISMISS_CANDIDATE -> type == ReportTargetType.AI_OUTPUT;
        };
    }
}
