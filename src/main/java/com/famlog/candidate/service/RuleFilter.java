package com.famlog.candidate.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 1차 규칙 필터 (사양서 6.2 ①, 비용 없음). 걸리면 대화가 30초 멈춘 뒤 바로 분석하고, 안 걸리면 보조 분석(30개·10분)을 기다린다.
 * 놓쳐도 보조 분석이 잡으므로 넓게 건다. 여기서 걸렸다고 후보가 생기는 것은 아니다 (LLM·서버 검증이 판단).
 */
public final class RuleFilter {

    private static final List<Pattern> PATTERNS = List.of(
            // 날짜·시간: 요일, 상대 날짜, N시·N시 반, N일, N월, 주말
            Pattern.compile("[월화수목금토일]요일|오늘|내일|모레|글피|이번\\s*주|다음\\s*주|다다음\\s*주|주말|이번\\s*달|다음\\s*달"),
            Pattern.compile("\\d{1,2}\\s*시|\\d{1,2}\\s*:\\s*\\d{2}|\\d{1,2}\\s*월|\\d{1,2}\\s*일|오전|오후|아침|점심|저녁"),
            Pattern.compile("(?:열한|열두|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*시(?![간작키도])"),
            // 할 일
            Pattern.compile("해야\\s*(?:해|돼|되|함|겠)|맡겨|맡기|사\\s*와|챙겨|잊지\\s*말|까먹지\\s*말|부탁|예약"),
            // 장소
            Pattern.compile("맛있|맛집|가\\s*볼\\s*만|가\\s*보자|가자|카페|식당|[가-힣]집"),
            Pattern.compile("map\\.kakao|place\\.map|naver\\.me|maps\\.app"),
            // 기념일
            Pattern.compile("생신|생일|기념일|제사|기일|환갑|칠순|돌잔치|결혼식"));

    private RuleFilter() {
    }

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }
}
