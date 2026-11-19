package com.famlog.candidate.service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Locale;

import com.famlog.candidate.domain.CandidateType;

/**
 * 후보 중복 기준 dedupKey (사양서 6.2 ② 중복 제거): 유형 + 정규화 제목 + 날짜, 장소는 카카오 장소 ID.
 * 제목은 공백·문장부호를 빼고 소문자로 맞춘다 ("할머니 댁 방문" = "할머니댁 방문!").
 */
public final class DedupKeys {

    private static final int MAX_LENGTH = 300;

    private DedupKeys() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 일정·할 일: 날짜(기한)가 없으면 빈 칸 */
    public static String of(CandidateType type, String title, LocalDate date) {
        return cut(type + "|" + normalize(title) + "|" + (date == null ? "" : date));
    }

    /** 기념일: 매년 반복이라 월·일 */
    public static String anniversary(String title, MonthDay monthDay) {
        return cut(CandidateType.ANNIVERSARY + "|" + normalize(title) + "|"
                + (monthDay == null ? "" : "%02d-%02d".formatted(monthDay.getMonthValue(), monthDay.getDayOfMonth())));
    }

    /** 장소: 카카오 장소가 정해졌으면 그 ID, 아니면 이름 */
    public static String place(String kakaoPlaceId, String name) {
        return cut(CandidateType.PLACE + "|"
                + (kakaoPlaceId != null ? "kakao:" + kakaoPlaceId : "name:" + normalize(name)));
    }

    private static String cut(String key) {
        return key.length() <= MAX_LENGTH ? key : key.substring(0, MAX_LENGTH);
    }
}
