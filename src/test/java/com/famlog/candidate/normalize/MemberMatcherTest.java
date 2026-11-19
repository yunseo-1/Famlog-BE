package com.famlog.candidate.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.famlog.candidate.normalize.MemberMatcher.Member;
import com.famlog.candidate.normalize.MemberMatcher.MatchResult;
import com.famlog.group.domain.FamilyRole;

/**
 * 참석자·담당자 매칭 (사양서 6.2 ②): 아빠/엄마/아들/딸 → family_role, 큰아들·막내 → family_title(우선),
 * 같은 역할 여러 명이면 모두 후보, "우리/다 같이" → 가족 전체(추정), "나" → 보낸 사람. 지금 구성원만 찾는다.
 */
class MemberMatcherTest {

    private static final long MOM = 1;
    private static final long DAD = 2;
    private static final long BIG_SON = 3;
    private static final long YOUNGEST = 4;
    private static final long DAUGHTER = 5;

    private static final List<Member> FAMILY = List.of(
            new Member(MOM, FamilyRole.MOTHER, null),
            new Member(DAD, FamilyRole.FATHER, null),
            new Member(BIG_SON, FamilyRole.SON, "큰아들"),
            new Member(YOUNGEST, FamilyRole.SON, "막내"),
            new Member(DAUGHTER, FamilyRole.DAUGHTER, null));

    @Test
    void 역할_이름으로_찾는다() {
        assertThat(match(MOM, "아빠").userIds()).containsExactly(DAD);
        assertThat(match(MOM, "아빠가").userIds()).containsExactly(DAD);
        assertThat(match(MOM, "아버지").userIds()).containsExactly(DAD);
        assertThat(match(DAD, "엄마한테").userIds()).containsExactly(MOM);
    }

    @Test
    void 여러_명을_한_번에_말하면_모두_찾는다() {
        assertThat(match(DAUGHTER, "엄마랑 아빠").userIds()).containsExactly(MOM, DAD);
        assertThat(match(DAD, "딸이랑 나").userIds()).containsExactly(DAUGHTER, DAD);
    }

    @Test
    void 호칭이_역할보다_먼저다() {
        assertThat(match(MOM, "큰아들").userIds()).containsExactly(BIG_SON);
        assertThat(match(MOM, "막내가").userIds()).containsExactly(YOUNGEST);
    }

    @Test
    void 같은_역할이_여러_명이면_모두_후보로_고르게_한다() {
        MatchResult result = match(MOM, "아들");

        assertThat(result.userIds()).isEmpty();
        assertThat(result.choices()).containsExactly(List.of(BIG_SON, YOUNGEST));
    }

    @Test
    void 우리_다_같이_모두_가자는_가족_전체_추정() {
        for (String text : List.of("우리", "다 같이", "모두", "가자", "먹자")) {
            MatchResult result = match(MOM, text);
            assertThat(result.everyone()).as(text).isTrue();
            assertThat(result.unmatched()).as(text).isEmpty();
        }
    }

    @Test
    void 부르는_말은_떼고_찾는다() {
        assertThat(match(DAD, "막내야").userIds()).containsExactly(YOUNGEST);
        assertThat(match(MOM, "딸아").userIds()).containsExactly(DAUGHTER);
        assertThat(match(MOM, "엄마야").userIds()).containsExactly(MOM);
    }

    @Test
    void 나는_보낸_사람이다() {
        assertThat(match(MOM, "나").userIds()).containsExactly(MOM);
        assertThat(match(BIG_SON, "내가").userIds()).containsExactly(BIG_SON);
    }

    @Test
    void 같은_사람은_한_번만() {
        assertThat(MemberMatcher.match(List.of("아빠", "아빠가"), MOM, FAMILY).userIds()).containsExactly(DAD);
    }

    @Test
    void 구성원이_아니거나_모르는_사람은_찾지_못한_것으로_남긴다() {
        assertThat(match(MOM, "할머니").unmatched()).containsExactly("할머니");
        assertThat(match(MOM, "이모").unmatched()).containsExactly("이모");
        assertThat(match(MOM, "할머니").userIds()).isEmpty();
    }

    @Test
    void 나간_구성원은_찾지_않는다() {
        List<Member> withoutDaughter = FAMILY.stream().filter(m -> m.userId() != DAUGHTER).toList();

        MatchResult result = MemberMatcher.match(List.of("딸"), MOM, withoutDaughter);

        assertThat(result.userIds()).isEmpty();
        assertThat(result.unmatched()).containsExactly("딸");
    }

    @Test
    void 가족_역할을_정하지_않았으면_역할로_찾지_못한다() {
        List<Member> unset = List.of(new Member(MOM, null, null), new Member(DAD, null, null));

        assertThat(MemberMatcher.match(List.of("아빠"), MOM, unset).unmatched()).containsExactly("아빠");
    }

    private static MatchResult match(long senderId, String text) {
        return MemberMatcher.match(List.of(text), senderId, FAMILY);
    }
}
