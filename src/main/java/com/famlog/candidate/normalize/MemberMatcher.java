package com.famlog.candidate.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.famlog.group.domain.FamilyRole;

/**
 * LLM 이 뽑은 사람 표현을 그룹 구성원으로 바꾼다 (사양서 6.2 ② 참석자·담당자).
 * <ul>
 * <li>호칭(family_title: 큰아들·막내)이 먼저, 그다음 역할(family_role: 아빠·엄마·아들·딸·할아버지·할머니)</li>
 * <li>같은 역할이 여러 명이면 모두 후보로 돌려 사용자가 고르게 한다</li>
 * <li>"우리·다 같이·모두" → 가족 전체(추정), "나·내가" → 보낸 사람</li>
 * <li>"엄마랑 아빠"처럼 이어 말하면 나눠서 찾고, 뒤에 붙은 조사(가·랑·한테…)는 떼고 본다</li>
 * <li>지금 구성원(members)만 찾는다. 못 찾은 표현은 그대로 남긴다 (카드에 글자로 보여 준다)</li>
 * </ul>
 */
public final class MemberMatcher {

    /** @param familyTitle 그룹 호칭 (없으면 null) */
    public record Member(long userId, FamilyRole familyRole, String familyTitle) {
    }

    /**
     * @param userIds   정해진 구성원 (말한 순서, 중복 없음)
     * @param choices   같은 역할이 여러 명이라 사용자가 골라야 하는 후보 묶음
     * @param everyone  가족 전체로 추정
     * @param unmatched 찾지 못한 표현
     */
    public record MatchResult(List<Long> userIds, List<List<Long>> choices, boolean everyone,
            List<String> unmatched) {
    }

    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:,|/|및|그리고|이랑|하고|랑)\\s*|(?<=[가-힣])[와과]\\s+");
    private static final List<String> JOSA = List.of("", "가", "이", "은", "는", "도", "을", "를", "랑", "이랑", "하고",
            "한테", "에게", "께", "께서", "와", "과", "님", "님이", "님은", "님도", "님께", "님한테");
    private static final List<String> SELF = List.of("나", "내", "저", "제");
    private static final List<String> EVERYONE = List.of("우리", "다같이", "모두", "다들", "가족", "온가족", "전부", "다함께",
            "우리가족");
    private static final Map<String, FamilyRole> ROLE_WORDS = Map.ofEntries(
            Map.entry("아빠", FamilyRole.FATHER), Map.entry("아버지", FamilyRole.FATHER),
            Map.entry("아버님", FamilyRole.FATHER),
            Map.entry("엄마", FamilyRole.MOTHER), Map.entry("어머니", FamilyRole.MOTHER),
            Map.entry("어머님", FamilyRole.MOTHER),
            Map.entry("아들", FamilyRole.SON), Map.entry("딸", FamilyRole.DAUGHTER),
            Map.entry("할아버지", FamilyRole.GRANDFATHER), Map.entry("할머니", FamilyRole.GRANDMOTHER));

    private MemberMatcher() {
    }

    public static MatchResult match(List<String> texts, long senderId, List<Member> members) {
        Set<Long> userIds = new LinkedHashSet<>();
        List<List<Long>> choices = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        boolean everyone = false;
        for (String text : texts == null ? List.<String>of() : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String token : SEPARATOR.split(text.strip())) {
                String word = token.replaceAll("\\s+", "");
                if (word.isEmpty()) {
                    continue;
                }
                if (matches(word, EVERYONE)) {
                    everyone = true;
                    continue;
                }
                Optional<Member> titled = members.stream()
                        .filter(m -> m.familyTitle() != null
                                && matches(word, List.of(m.familyTitle().replaceAll("\\s+", ""))))
                        .findFirst();
                if (titled.isPresent()) {
                    userIds.add(titled.get().userId());
                    continue;
                }
                if (matches(word, SELF)) {
                    userIds.add(senderId);
                    continue;
                }
                FamilyRole role = ROLE_WORDS.entrySet().stream().filter(e -> matches(word, List.of(e.getKey())))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                List<Long> withRole = role == null ? List.of()
                        : members.stream().filter(m -> m.familyRole() == role).map(Member::userId).toList();
                if (withRole.size() == 1) {
                    userIds.add(withRole.get(0));
                } else if (withRole.size() > 1) {
                    if (!choices.contains(withRole)) {
                        choices.add(withRole);
                    }
                } else {
                    unmatched.add(token.strip());
                }
            }
        }
        return new MatchResult(List.copyOf(userIds), List.copyOf(choices), everyone, List.copyOf(unmatched));
    }

    /** word 가 후보 단어 그대로이거나 단어 + 조사 */
    private static boolean matches(String word, List<String> candidates) {
        for (String candidate : candidates) {
            if (word.startsWith(candidate) && JOSA.contains(word.substring(candidate.length()))) {
                return true;
            }
        }
        return false;
    }
}
