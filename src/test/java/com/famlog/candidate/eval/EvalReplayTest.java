package com.famlog.candidate.eval;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.famlog.candidate.dto.ExtractDtos.ExtractedCandidate;
import com.famlog.candidate.dto.ExtractDtos.ExtractedEvidence;
import com.famlog.candidate.normalize.MemberMatcher;
import com.famlog.candidate.repository.CandidateEvidenceWriter.EvidenceMessage;
import com.famlog.candidate.service.CandidateBuilder;
import com.famlog.candidate.service.CandidateBuilder.Built;
import com.famlog.candidate.service.CandidatePlaceResolver;
import com.famlog.candidate.service.DedupKeys;
import com.famlog.group.domain.FamilyRole;
import com.famlog.support.FakeKakaoLocalClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 평가셋 2단계 (사양서 7.3): 워커가 실제 LLM 으로 만든 결과(worker/evals/out/results.jsonl)를 서버 후보 생성 코드에 통과시켜
 * 정답(worker/evals/cases.jsonl)과 비교한다. 일치율·오탐률·날짜 정확도·6.4 비생성 비율을 출력한다.
 * <p>
 * 평소 테스트·CI 에서는 돌지 않는다 ({@code ./gradlew evalReplay}). 결과 파일이 없으면 건너뛴다.
 * 장소는 카카오를 부르지 않고 이름으로만 비교한다. 구성원은 M1 → 1, M2 → 2 … 로 둔다.
 */
@Tag("eval")
class EvalReplayTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final CandidateBuilder builder = new CandidateBuilder(
            new CandidatePlaceResolver(new FakeKakaoLocalClient(), null, null));

    @Test
    void 평가셋_지표를_출력한다() throws IOException {
        Path root = Path.of(System.getProperty("famlog.repoRoot", "."), "worker", "evals");
        Path resultsFile = root.resolve("out/results.jsonl");
        assumeTrue(Files.exists(resultsFile), "worker/evals/out/results.jsonl 이 없습니다 (python -m evals.run 먼저)");
        Map<String, JsonNode> results = new LinkedHashMap<>();
        for (String line : Files.readAllLines(resultsFile)) {
            if (!line.isBlank()) {
                JsonNode node = json.readTree(line);
                results.put(node.get("id").asString(), node);
            }
        }

        int expectedTotal = 0;
        int matched = 0;
        int produced = 0;
        int dateChecked = 0;
        int dateCorrect = 0;
        int noneCases = 0;
        int noneClean = 0;
        int failedCalls = 0;
        List<String> misses = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("cases.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode testCase = json.readTree(line);
            String id = testCase.get("id").asString();
            JsonNode result = results.get(id);
            if (result == null || !"DONE".equals(result.get("status").asString())) {
                failedCalls++;
                misses.add(id + ": 결과 없음/실패 " + (result == null ? "" : result.get("errorCode")));
                continue;
            }
            List<Built> built = replay(testCase, result);
            List<JsonNode> expected = new ArrayList<>();
            testCase.get("expected").forEach(expected::add);
            expectedTotal += expected.size();
            produced += built.size();
            if (expected.isEmpty()) {
                noneCases++;
                if (built.isEmpty()) {
                    noneClean++;
                }
            }
            List<Built> unmatched = new ArrayList<>(built);
            for (JsonNode want : expected) {
                Optional<Built> hit = unmatched.stream().filter(b -> matches(want, b)).findFirst();
                if (hit.isPresent()) {
                    matched++;
                    unmatched.remove(hit.get());
                } else {
                    misses.add(id + ": 못 맞힘 " + want + " / 만든 것 " + summary(built));
                }
                if (dateKey(want) != null) {
                    List<Built> sameType = built.stream().filter(b -> b.type().name().equals(want.get("type")
                            .asString())).toList();
                    if (!sameType.isEmpty()) {
                        dateChecked++;
                        if (sameType.stream().anyMatch(b -> dateMatches(want, b))) {
                            dateCorrect++;
                        }
                    }
                }
            }
            unmatched.forEach(extra -> misses.add(id + ": 오탐 " + summary(List.of(extra))));
        }

        System.out.printf("%n===== 평가 결과 (사양서 7.3) =====%n");
        System.out.printf("일치율(맞힌 정답/정답)      %d/%d = %s%n", matched, expectedTotal, pct(matched, expectedTotal));
        System.out.printf("오탐률(정답 아닌 후보/후보) %d/%d = %s  (목표 15%% 이하)%n", produced - matched, produced,
                pct(produced - matched, produced));
        System.out.printf("날짜 정확도                 %d/%d = %s  (목표 90%% 이상)%n", dateCorrect, dateChecked,
                pct(dateCorrect, dateChecked));
        System.out.printf("6.4 비생성                  %d/%d = %s%n", noneClean, noneCases, pct(noneClean, noneCases));
        System.out.printf("호출 실패·결과 없음         %d건%n", failedCalls);
        misses.forEach(miss -> System.out.println("  - " + miss));
    }

    /** 서버와 같은 순서: 근거 확인 → 기준 메시지 → 정규화 → 같은 결과 안 중복 제거 */
    private List<Built> replay(JsonNode testCase, JsonNode result) {
        List<MemberMatcher.Member> members = new ArrayList<>();
        Map<String, Long> refs = new LinkedHashMap<>();
        for (JsonNode m : testCase.get("members")) {
            long userId = refs.size() + 1;
            refs.put(m.get("ref").asString(), userId);
            String title = m.get("familyTitle").isNull() ? null : m.get("familyTitle").asString();
            members.add(new MemberMatcher.Member(userId, FamilyRole.valueOf(m.get("familyRole").asString()), title));
        }
        Map<Long, EvidenceMessage> messages = new LinkedHashMap<>();
        long seq = 0;
        Instant last = Instant.EPOCH;
        for (JsonNode m : testCase.get("messages")) {
            Instant sentAt = OffsetDateTime.parse(m.get("sentAt").asString()).toInstant();
            last = sentAt;
            messages.put(m.get("id").asLong(), new EvidenceMessage(m.get("id").asLong(), 1, ++seq,
                    refs.get(m.get("sender").asString()), m.get("text").asString(), sentAt));
        }
        CandidateBuilder.Context context = new CandidateBuilder.Context(members, List.of(), last);
        List<Built> built = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : result.get("candidates")) {
            ExtractedCandidate candidate = json.treeToValue(node, ExtractedCandidate.class);
            List<EvidenceMessage> evidence = new ArrayList<>();
            for (ExtractedEvidence e : candidate.evidence()) {
                EvidenceMessage message = messages.get(e.messageId());
                if (message == null) {
                    evidence.clear();
                    break;
                }
                evidence.add(message);
            }
            if (evidence.isEmpty()) {
                continue;
            }
            builder.build(candidate, CandidateBuilder.anchorOf(candidate, evidence), context)
                    .filter(b -> keys.add(b.dedupKey()))
                    .ifPresent(built::add);
        }
        return built;
    }

    private static boolean matches(JsonNode want, Built b) {
        if (!b.type().name().equals(want.get("type").asString())) {
            return false;
        }
        Map<String, Object> f = b.fields();
        for (Map.Entry<String, JsonNode> entry : want.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            boolean ok = switch (key) {
                case "type" -> true;
                case "date", "startTime", "dueDate" -> Objects.equals(value.isNull() ? null : value.asString(),
                        f.get(key));
                case "month", "day" -> Objects.equals(value.asInt(), f.get(key));
                case "name" -> f.get("name") != null && DedupKeys.normalize((String) f.get("name"))
                        .contains(DedupKeys.normalize(value.asString()));
                case "assignees" -> {
                    Set<Long> wantIds = new HashSet<>();
                    value.forEach(ref -> wantIds.add(Long.parseLong(ref.asString().substring(1))));
                    yield new HashSet<>((List<?>) f.get("assigneeIds")).equals(wantIds);
                }
                default -> throw new IllegalArgumentException("모르는 정답 키: " + key);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String dateKey(JsonNode want) {
        for (String key : List.of("date", "dueDate", "month")) {
            if (want.has(key) && !want.get(key).isNull()) {
                return key;
            }
        }
        return null;
    }

    private static boolean dateMatches(JsonNode want, Built b) {
        String key = dateKey(want);
        if ("month".equals(key)) {
            return Objects.equals(want.get("month").asInt(), b.fields().get("month"))
                    && Objects.equals(want.get("day").asInt(), b.fields().get("day"));
        }
        return Objects.equals(want.get(key).asString(), b.fields().get(key));
    }

    private static String summary(List<Built> built) {
        return built.stream().map(b -> b.type().name() + b.fields().entrySet().stream()
                .filter(e -> List.of("title", "name", "date", "startTime", "dueDate", "month", "day", "assigneeIds")
                        .contains(e.getKey()))
                .toList()).toList().toString();
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : "%.1f%%".formatted(100.0 * a / b);
    }
}
