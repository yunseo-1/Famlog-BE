package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 1차 규칙 필터 (사양서 6.2 ①): 날짜·시간, 할 일, 장소, 기념일 표현. */
class RuleFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // 6.3 예시
            "이번 토요일 11시에 할머니 댁 가자",
            "아빠가 이번 주에 자동차 점검 맡겨야 해",
            "잠실에 있는 OO칼국수 정말 맛있더라",
            "다음 달 15일이 할머니 생신이야",
            "다음 주 토요일 6시에 할머니댁 저녁",
            // 6.2 ① 표현
            "내일 시간 돼?", "모레 보자", "글피에 와", "7:30에 출발", "다섯 시에 만나", "3월에 이사해",
            "우유 사와", "잊지 말고 약 챙겨", "세탁소 맡겨 줘", "숙제 해야 돼",
            "거기 가볼 만해", "새로 생긴 카페", "그 국밥집 어때", "https://place.map.kakao.com/123",
            "엄마 생일 선물", "결혼 기념일", "할아버지 제사", "할머니 기일"
    })
    void 걸린다(String text) {
        assertThat(RuleFilter.matches(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㅋㅋㅋ", "응", "고마워", "사랑해요", "밥 먹었어?", "다시 말해 줘", "한참 걸렸네", "  "})
    void 일상_대화는_걸리지_않는다(String text) {
        assertThat(RuleFilter.matches(text)).isFalse();
    }
}
