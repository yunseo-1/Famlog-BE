package com.famlog.candidate.service;

import java.util.regex.Pattern;

/**
 * LLM 전송 전 마스킹 (사양서 6.2 ①, 8.7, 9.7). 전화번호·계좌번호·카드번호·주민등록번호·이메일을 표시 문자로 바꾼다.
 * 날짜·시간·금액("11시", "15일", "2026-11-14", "3만원")은 인식에 필요하므로 건드리지 않는다.
 * 애매한 긴 숫자열(하이픈 포함 10자리 이상)은 계좌번호로 본다.
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 주민등록번호·외국인등록번호: 6자리-7자리 (뒷자리 첫 숫자 1~8) */
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}\\s?-\\s?[1-8]\\d{6}(?!\\d)");

    /** 카드번호: 4자리씩 4묶음 (구분자 하이픈·공백 또는 없음) */
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}(?!\\d)");

    /** 휴대전화·지역번호 전화, 대표번호(1588-0000) */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+82[- ]?|(?<!\\d)0)\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)|(?<!\\d)1[5-9]\\d{2}-\\d{4}(?!\\d)");

    /** 계좌번호: 숫자 묶음 2~4개를 하이픈으로 이은 10자리 이상, 또는 하이픈 없는 10~14자리 */
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?<![\\d-])\\d{2,6}(?:-\\d{2,8}){1,3}(?![\\d-])|(?<!\\d)\\d{10,14}(?!\\d)");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = EMAIL.matcher(text).replaceAll("[이메일]");
        masked = RRN.matcher(masked).replaceAll("[주민등록번호]");
        masked = CARD.matcher(masked).replaceAll("[카드번호]");
        masked = PHONE.matcher(masked).replaceAll("[전화번호]");
        return ACCOUNT.matcher(masked).replaceAll(match -> isAccount(match.group()) ? "[계좌번호]" : match.group());
    }

    /** 하이픈으로 이은 숫자가 10자리 이상일 때만 계좌번호로 본다 (날짜 2026-11-14 는 8자리라 제외) */
    private static boolean isAccount(String candidate) {
        return candidate.replace("-", "").length() >= 10;
    }
}
