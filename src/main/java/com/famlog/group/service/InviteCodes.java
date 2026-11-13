package com.famlog.group.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * 초대 코드 형식 (사양서 4.1 GRP-02, 9.1): 8자리, 혼동 문자 0/O/1/I 를 뺀 32문자 → 약 1조 가지.
 * 사람이 받아 적기 쉽도록 입력은 대소문자·공백·하이픈을 가리지 않는다.
 */
final class InviteCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** 입력을 정규화한다. 형식이 맞지 않으면 empty (없는 코드와 똑같이 실패로 센다) */
    static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH || !code.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(code);
    }
}
