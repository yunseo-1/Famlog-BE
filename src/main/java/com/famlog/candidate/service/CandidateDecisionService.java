package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.candidate.domain.CandidateStatus;
import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.domain.FeedbackAction;
import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.candidate.domain.SuggestionFeedback;
import com.famlog.candidate.dto.CandidateDtos.ApprovePlace;
import com.famlog.candidate.dto.CandidateDtos.ApproveRequest;
import com.famlog.candidate.dto.CandidateDtos.CandidateResolved;
import com.famlog.candidate.dto.CandidateDtos.CandidateResponse;
import com.famlog.candidate.dto.CandidateDtos.DecisionResponse;
import com.famlog.candidate.dto.CandidateDtos.DismissRequest;
import com.famlog.candidate.event.CandidateEvents.CandidateApproved;
import com.famlog.candidate.event.CandidateEvents.CandidateUndone;
import com.famlog.candidate.repository.AiSourceReader;
import com.famlog.candidate.repository.CandidateEvidenceWriter;
import com.famlog.candidate.repository.CandidateEvidenceWriter.Anchor;
import com.famlog.candidate.repository.CandidateEvidenceWriter.EvidenceRow;
import com.famlog.candidate.repository.RegistrationCandidateRepository;
import com.famlog.candidate.repository.SuggestionFeedbackRepository;
import com.famlog.candidate.service.CandidateConverter.Converted;
import com.famlog.candidate.service.CandidateConverter.PlaceDraft;
import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponse;
import com.famlog.common.source.ItemSources;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.domain.Place;
import com.famlog.place.service.PlaceSearchService;
import com.famlog.place.service.PlaceService;
import com.famlog.schedule.domain.Schedule;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleRequest;
import com.famlog.schedule.service.ScheduleService;
import com.famlog.todo.dto.TodoDtos.CreateTodoRequest;
import com.famlog.todo.service.TodoService;
import com.famlog.user.domain.User;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 후보 승인·무시·되돌리기 (사양서 6.2 ④⑤, 6.5 AR-4~7, 11.3 ARC-05). AI 결과로 실제 항목을 만드는 곳은 여기뿐이다.
 * <ul>
 * <li>구성원 누구나 처리한다. 다른 그룹 후보면 404. 동시에 눌러도 행 잠금으로 한 사람씩, 늦은 쪽은 409</li>
 * <li>승인: 한 트랜잭션으로 실제 항목 생성 → 원문 연결(item_sources) → APPROVED → 채팅 시스템 메시지(채팅 푸시) →
 * candidate.resolved. 값을 보내면 그 값으로 등록하고 후보 값과 다르면 EDITED</li>
 * <li>같은 사람이 이미 처리한 후보를 다시 처리하면 그 결과를 그대로 돌려준다 (재시도 안전)</li>
 * <li>되돌리기: 승인한 사람이나 ADMIN 이상이 5분 안에. 만든 항목과 시스템 메시지를 지운다
 * (이미 공유된 장소에 연결했으면 연결만 푼다)</li>
 * <li>기존 항목 변경·완료 후보(target_ref, S19-2): 새로 만들지 않고 그 일정의 날짜·시각을 바꾸거나 할 일을 완료한다.
 * 되돌리면 바꾸기 전 값으로 돌리거나 다시 연다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CandidateDecisionService {

    static final Duration UNDO_WINDOW = Duration.ofMinutes(5);

    private final RegistrationCandidateRepository candidateRepository;
    private final SuggestionFeedbackRepository feedbackRepository;
    private final CandidateEvidenceWriter evidence;
    private final AiSourceReader sourceReader;
    private final CandidateMapper mapper;
    private final GroupAccessChecker accessChecker;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ScheduleService scheduleService;
    private final TodoService todoService;
    private final PlaceService placeService;
    private final PlaceSearchService placeSearchService;
    private final ItemSources itemSources;
    private final ChatRelay relay;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public DecisionResponse approve(long candidateId, long userId, ApproveRequest request) {
        RegistrationCandidate candidate = lock(candidateId);
        accessChecker.requireMemberOrNotFound(candidate.getGroupId(), userId);
        if (candidate.getStatus() == CandidateStatus.APPROVED && Objects.equals(candidate.getDecidedBy(), userId)) {
            return response(candidate);
        }
        Instant now = Instant.now(clock);
        requireOpen(candidate, request.version(), now);

        if (candidate.hasTarget()) {
            return applyTarget(candidate, userId, request, now);
        }
        List<EvidenceRow> rows = evidence.evidence(candidateId);
        Long senderId = rows.isEmpty() ? null : rows.getLast().senderId();
        List<Long> members = sourceReader.members(candidate.getGroupId()).stream()
                .map(AiSourceReader.Member::userId).toList();
        JsonNode fields = jsonMapper.readTree(candidate.getFields());
        Converted converted = CandidateConverter.convert(candidate, fields, members,
                senderId == null ? -1 : senderId, LocalDate.ofInstant(now, FamlogTime.DISPLAY_ZONE));

        Created created = switch (candidate.getType()) {
            case SCHEDULE, ANNIVERSARY -> createSchedule(candidate, userId, request, converted);
            case TODO -> createTodo(candidate, userId, request, converted);
            case PLACE -> createPlace(candidate, userId, request, converted, fields);
        };
        itemSources.add(created.sourceType(), created.entityId(), rows.stream().map(EvidenceRow::messageId).toList());
        candidate.approve(userId, now, created.entityType(), created.entityId());
        candidateRepository.saveAndFlush(candidate);
        feedbackRepository.save(new SuggestionFeedback(candidateId, userId,
                created.edited().isEmpty() ? FeedbackAction.APPROVED : FeedbackAction.EDITED, created.edited(),
                null));
        events.publishEvent(new CandidateApproved(candidate.getGroupId(), candidateId, userId,
                approvedText(candidate.getType(), created.title()), created.sourceType(), created.entityId()));
        resolved(candidate);
        return response(candidate);
    }

    @Transactional
    public DecisionResponse dismiss(long candidateId, long userId, DismissRequest request) {
        RegistrationCandidate candidate = lock(candidateId);
        accessChecker.requireMemberOrNotFound(candidate.getGroupId(), userId);
        if (candidate.getStatus() == CandidateStatus.DISMISSED && Objects.equals(candidate.getDecidedBy(), userId)) {
            return response(candidate);
        }
        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw alreadyDecided(candidate);
        }
        if (request.version() != candidate.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        candidate.dismiss(userId, Instant.now(clock), request.reason());
        candidateRepository.saveAndFlush(candidate);
        feedbackRepository.save(new SuggestionFeedback(candidateId, userId, FeedbackAction.DISMISSED, List.of(),
                request.reason()));
        resolved(candidate);
        return response(candidate);
    }

    @Transactional
    public DecisionResponse undo(long candidateId, long userId) {
        RegistrationCandidate candidate = lock(candidateId);
        GroupRole role = accessChecker.requireMemberOrNotFound(candidate.getGroupId(), userId);
        if (candidate.getStatus() == CandidateStatus.UNDONE) {
            return response(candidate);
        }
        if (candidate.getStatus() != CandidateStatus.APPROVED) {
            throw alreadyDecided(candidate);
        }
        boolean allowed = Objects.equals(candidate.getDecidedBy(), userId) || role.atLeast(GroupRole.ADMIN);
        if (!allowed || !Instant.now(clock).isBefore(candidate.getDecidedAt().plus(UNDO_WINDOW))) {
            throw new BusinessException(ErrorCode.CANDIDATE_NOT_UNDOABLE);
        }
        if (candidate.hasTarget()) {
            restoreTarget(candidate, userId);
        } else {
            removeCreated(candidate, userId);
        }
        candidate.undo();
        candidateRepository.saveAndFlush(candidate);
        feedbackRepository.save(new SuggestionFeedback(candidateId, userId, FeedbackAction.UNDONE, List.of(), null));
        events.publishEvent(new CandidateUndone(candidate.getGroupId(), candidateId));
        resolved(candidate);
        return response(candidate);
    }

    /**
     * 기존 항목 변경·완료 후보 승인 (S19-2). 대상이 여럿이면 targetId 로 고른다(없으면 422). 일정은 날짜·시각만 바꾸고
     * (수정 권한이 없으면 403), 할 일은 완료로 바꾼다. 그사이 대상이 바뀌었거나 지워졌으면 409 CANDIDATE_TARGET_CHANGED.
     * 바꾼 대상과 바꾼 뒤 version 을 target_ref 에 남긴다 (되돌리기용). 원문 연결(item_sources)은 만들지 않는다
     */
    private DecisionResponse applyTarget(RegistrationCandidate candidate, long userId, ApproveRequest request,
            Instant now) {
        requireOnly(request.schedule() == null && request.todo() == null && request.place() == null);
        Map<String, Object> target = targetOf(candidate);
        Long chosen = request.targetId() != null ? request.targetId() : asLong(target.get("entityId"));
        if (chosen == null) {
            throw missing(List.of(CandidateTargets.MISSING_TARGET));
        }
        Map<String, Object> choice = choices(target).stream()
                .filter(c -> chosen.equals(asLong(c.get("entityId")))).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "고를 수 있는 항목이 아니에요."));
        List<String> edited = target.get("entityId") == null ? List.of(CandidateTargets.MISSING_TARGET) : List.of();
        String entityType = (String) target.get("entityType");
        String what;
        Map<String, Object> applied = new LinkedHashMap<>();
        try {
            if (CandidateTargets.CHANGE.equals(target.get("action"))) {
                Schedule.Timing before = timing(choice.get("before"));
                Schedule.Timing after = timing(choice.get("after"));
                ScheduleService.Retimed retimed = scheduleService.retime(chosen, userId, before, after, null);
                applied.put("version", retimed.version());
                what = "님이 '" + retimed.title() + "' 일정을 " + changeText(before, after) + " 바꿨어요.";
            } else {
                what = "님이 '" + todoService.completeFromCandidate(chosen, userId) + "' 할 일을 완료했어요.";
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new BusinessException(ErrorCode.CANDIDATE_TARGET_CHANGED);
            }
            throw e;
        }
        target.put("entityId", chosen);
        target.put("title", choice.get("title"));
        if (choice.containsKey("createdBy")) {
            target.put("createdBy", choice.get("createdBy"));
        }
        target.put("before", choice.get("before"));
        if (choice.containsKey("after")) {
            target.put("after", choice.get("after"));
        }
        target.put(CandidateMapper.APPLIED, applied);
        candidate.recordTarget(jsonMapper.writeValueAsString(target));
        candidate.approve(userId, now, entityType, chosen);
        candidateRepository.saveAndFlush(candidate);
        feedbackRepository.save(new SuggestionFeedback(candidate.getId(), userId,
                edited.isEmpty() ? FeedbackAction.APPROVED : FeedbackAction.EDITED, edited, null));
        events.publishEvent(new CandidateApproved(candidate.getGroupId(), candidate.getId(), userId, what,
                entityType, chosen));
        resolved(candidate);
        return response(candidate);
    }

    /** 변경 후보는 바꾸기 전 시각으로 돌린다 (그사이 일정이 또 바뀌었으면 409). 완료 후보는 할 일을 다시 연다. 지워졌으면 할 것이 없다 */
    private void restoreTarget(RegistrationCandidate candidate, long userId) {
        Map<String, Object> target = targetOf(candidate);
        long id = candidate.getCreatedEntityId();
        try {
            if (CandidateTargets.CHANGE.equals(target.get("action"))) {
                Object version = ((Map<?, ?>) target.get(CandidateMapper.APPLIED)).get("version");
                scheduleService.retime(id, userId, timing(target.get("after")), timing(target.get("before")),
                        asLong(version));
            } else {
                todoService.reopenFromCandidate(id, userId);
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND) {
                throw e;
            }
        }
    }

    private Map<String, Object> targetOf(RegistrationCandidate candidate) {
        return jsonMapper.readValue(candidate.getTargetRef(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> choices(Map<String, Object> target) {
        return (List<Map<String, Object>>) target.getOrDefault("choices", List.of());
    }

    private static Schedule.Timing timing(Object value) {
        Map<?, ?> map = (Map<?, ?>) value;
        return new Schedule.Timing(Instant.parse((String) map.get("startsAt")),
                Instant.parse((String) map.get("endsAt")), Boolean.TRUE.equals(map.get("allDay")));
    }

    /** "12:00으로", "11월 15일(일)로", "11월 15일(일) 12:00으로" */
    static String changeText(Schedule.Timing before, Schedule.Timing after) {
        ZonedDateTime from = before.startsAt().atZone(FamlogTime.DISPLAY_ZONE);
        ZonedDateTime to = after.startsAt().atZone(FamlogTime.DISPLAY_ZONE);
        boolean dateChanged = !from.toLocalDate().equals(to.toLocalDate());
        String date = "%d월 %d일(%s)".formatted(to.getMonthValue(), to.getDayOfMonth(),
                to.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
        String time = "%02d:%02d".formatted(to.getHour(), to.getMinute());
        String text = after.allDay() ? date : dateChanged ? date + " " + time : time;
        return text + particle(text);
    }

    /** 받침이 있으면(ㄹ 제외) "으로", 없으면 "로". 숫자는 읽는 소리로 (0 영·3 삼·6 육 → 으로). 괄호는 앞 글자로 본다 */
    private static String particle(String text) {
        char last = text.charAt(text.length() - 1) == ')' ? text.charAt(text.length() - 2) : text.charAt(
                text.length() - 1);
        if (Character.isDigit(last)) {
            return "036".indexOf(last) >= 0 ? "으로" : "로";
        }
        int jong = last >= 0xAC00 && last <= 0xD7A3 ? (last - 0xAC00) % 28 : 0;
        return jong == 0 || jong == 8 ? "로" : "으로";
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 승인으로 만든 것 (sourceType 은 item_sources 의 종류, entityType 은 후보에 남기는 값) */
    private record Created(String entityType, String sourceType, long entityId, String title, List<String> edited) {
    }

    private Created createSchedule(RegistrationCandidate candidate, long userId, ApproveRequest request,
            Converted converted) {
        requireOnly(request.schedule() != null || (request.todo() == null && request.place() == null));
        ScheduleRequest base = converted.schedule();
        ScheduleRequest chosen = request.schedule() != null ? request.schedule() : base;
        if (chosen == null) {
            throw missing(converted.missing());
        }
        List<String> edited = request.schedule() == null ? List.of() : scheduleChanges(base, chosen, converted);
        long id = scheduleService.create(candidate.getGroupId(), userId, chosen).id();
        return new Created(ItemSources.SCHEDULE, ItemSources.SCHEDULE, id, chosen.title().strip(), edited);
    }

    private Created createTodo(RegistrationCandidate candidate, long userId, ApproveRequest request,
            Converted converted) {
        requireOnly(request.todo() != null || (request.schedule() == null && request.place() == null));
        CreateTodoRequest base = converted.todo();
        CreateTodoRequest chosen = request.todo() != null ? request.todo() : base;
        if (chosen == null) {
            throw missing(converted.missing());
        }
        List<String> edited = new ArrayList<>();
        if (request.todo() != null) {
            if (base == null) {
                edited.addAll(converted.missing());
            } else {
                diff(edited, "title", base.title().strip(), chosen.title().strip());
                diff(edited, "assigneeId", base.assigneeId(), chosen.assigneeId());
                diff(edited, "dueDate", base.dueDate(), chosen.dueDate());
            }
        }
        long id = todoService.create(candidate.getGroupId(), userId, chosen).id();
        return new Created(ItemSources.TODO, ItemSources.TODO, id, chosen.title().strip(), edited);
    }

    private Created createPlace(RegistrationCandidate candidate, long userId, ApproveRequest request,
            Converted converted, JsonNode fields) {
        requireOnly(request.place() != null || (request.schedule() == null && request.todo() == null));
        PlaceDraft base = converted.place();
        PlaceDraft chosen = request.place() != null ? draftOf(request.place(), fields) : base;
        if (chosen == null) {
            throw missing(converted.missing());
        }
        List<String> edited = new ArrayList<>();
        if (request.place() != null) {
            if (base == null) {
                edited.addAll(converted.missing());
            } else {
                diff(edited, "kakaoPlaceId", base.info().kakaoPlaceId(), chosen.info().kakaoPlaceId());
                diff(edited, "category", base.category(), chosen.category());
                diff(edited, "oneLineReview", blankToNull(base.oneLineReview()), blankToNull(chosen.oneLineReview()));
            }
        }
        String review = chosen.oneLineReview() == null || chosen.oneLineReview().length() <= 100
                ? chosen.oneLineReview() : chosen.oneLineReview().substring(0, 100);
        PlaceService.Registered registered = placeService.registerFromCandidate(candidate.getGroupId(), userId,
                chosen.info(), chosen.category(), review);
        return new Created(registered.created() ? ItemSources.PLACE : CandidateMapper.PLACE_LINKED, ItemSources.PLACE,
                registered.place().getId(), registered.place().getName(), edited);
    }

    /** 수정 폼에서 고른 장소: 후보의 options 중 하나, 아니면 방금 검색한 장소 */
    private PlaceDraft draftOf(ApprovePlace place, JsonNode fields) {
        JsonNode option = CandidateConverter.option(fields.path("options"), place.kakaoPlaceId());
        Place.KakaoInfo info = option != null ? CandidateConverter.info(option)
                : placeSearchService.recentDocument(place.kakaoPlaceId()).map(placeSearchService::infoOf)
                        .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                                "검색한 지 오래된 장소예요. 다시 검색해서 골라 주세요."));
        return new PlaceDraft(info, place.category(), place.oneLineReview());
    }

    /** 고친 항목 이름 (값은 남기지 않는다) */
    private static List<String> scheduleChanges(ScheduleRequest base, ScheduleRequest chosen, Converted converted) {
        List<String> edited = new ArrayList<>();
        if (base == null) {
            edited.addAll(converted.missing());
            return edited;
        }
        diff(edited, "title", base.title().strip(), chosen.title().strip());
        diff(edited, "allDay", base.allDay(), chosen.allDay());
        diff(edited, "startsAt", instant(base.startsAt()), instant(chosen.startsAt()));
        diff(edited, "endsAt", instant(base.endsAt()), instant(chosen.endsAt()));
        diff(edited, "startDate", base.startDate(), chosen.startDate());
        diff(edited, "endDate", base.endDate(), chosen.endDate());
        diff(edited, "participantIds", base.participantIds(), Set.copyOf(chosen.participantIds()));
        diff(edited, "placeId", base.placeId(), chosen.placeId());
        diff(edited, "memo", blankToNull(base.memo()), blankToNull(chosen.memo()));
        diff(edited, "rrule", blankToNull(base.rrule()), blankToNull(chosen.rrule()));
        if (chosen.reminders() != null) {
            edited.add("reminders");
        }
        return edited;
    }

    private void removeCreated(RegistrationCandidate candidate, long userId) {
        String type = candidate.getCreatedEntityType();
        long id = candidate.getCreatedEntityId();
        try {
            switch (type) {
                case ItemSources.SCHEDULE -> scheduleService.delete(id, userId);
                case ItemSources.TODO -> todoService.delete(id, userId);
                case ItemSources.PLACE -> placeService.delete(id, userId);
                default -> itemSources.remove(ItemSources.PLACE, id,
                        evidence.evidence(candidate.getId()).stream().map(EvidenceRow::messageId).toList());
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND) {
                throw e;
            }
            // 그사이 누가 지웠다: 지울 것이 없다
        }
    }

    private RegistrationCandidate lock(long candidateId) {
        return candidateRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireOpen(RegistrationCandidate candidate, Long version, Instant now) {
        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw alreadyDecided(candidate);
        }
        if (candidate.isExpired(now)) {
            throw new BusinessException(ErrorCode.CANDIDATE_EXPIRED);
        }
        if (version != candidate.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    /** "엄마님이 먼저 등록했어요." (AR-6) */
    private BusinessException alreadyDecided(RegistrationCandidate candidate) {
        String name = candidate.getDecidedBy() == null ? "다른 가족"
                : nameOf(candidate.getGroupId(), candidate.getDecidedBy()) + "님";
        String what = switch (candidate.getStatus()) {
            case APPROVED -> "이 먼저 등록했어요.";
            case DISMISSED -> "이 먼저 무시했어요.";
            case UNDONE -> "이 등록했다가 되돌렸어요.";
            default -> "이 먼저 처리했어요.";
        };
        return new BusinessException(ErrorCode.CANDIDATE_ALREADY_DECIDED,
                candidate.getStatus() == CandidateStatus.EXPIRED ? "시간이 지난 제안이에요." : name + what);
    }

    private static BusinessException missing(List<String> fields) {
        ErrorCode code = ErrorCode.CANDIDATE_MISSING_FIELDS;
        return new BusinessException(code, code.getMessage(),
                fields.stream().map(f -> new ErrorResponse.FieldError(f, "채우거나 골라 주세요.")).toList());
    }

    private static void requireOnly(boolean matchesType) {
        if (!matchesType) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "후보 유형에 맞는 값을 보내 주세요.");
        }
    }

    private static String approvedText(CandidateType type, String title) {
        return switch (type) {
            case SCHEDULE -> "님이 '" + title + "' 일정을 캘린더에 등록했어요.";
            case ANNIVERSARY -> "님이 '" + title + "' 기념일을 캘린더에 등록했어요.";
            case TODO -> "님이 '" + title + "' 할 일을 등록했어요.";
            case PLACE -> "님이 '" + title + "' 장소를 우리 가족 장소에 추가했어요.";
        };
    }

    private void resolved(RegistrationCandidate c) {
        CandidateResponse response = response(c).candidate();
        relay.toGroup(c.getGroupId(), ChatEvent.CANDIDATE_RESOLVED, new CandidateResolved(c.getGroupId(), c.getId(),
                c.getStatus(), c.getDecidedBy(), c.getDecidedBy() == null ? null : nameOf(c.getGroupId(),
                        c.getDecidedBy()), response.entityType(), response.entityId(), response.linkedExisting()));
    }

    private DecisionResponse response(RegistrationCandidate candidate) {
        Anchor anchor = evidence.anchors(List.of(candidate.getId())).get(candidate.getId());
        return new DecisionResponse(mapper.toResponse(candidate, anchor));
    }

    /** 시스템 메시지와 같은 규칙: 그룹 호칭이 있으면 호칭, 없으면 닉네임 */
    private String nameOf(long groupId, long userId) {
        return memberRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMember::getFamilyTitle)
                .or(() -> userRepository.findById(userId).map(User::getNickname))
                .orElse("가족");
    }

    private static <T> void diff(List<String> edited, String name, T before, T after) {
        if (!Objects.equals(before, after)) {
            edited.add(name);
        }
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
