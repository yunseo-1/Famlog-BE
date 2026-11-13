package com.famlog.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 초대 코드 형식 (사양서 4.1 GRP-02, 9.1: 32문자 8자리, 혼동 문자 0/O/1/I 제외). */
class InviteCodesTest {

    @Test
    void 알파벳은_혼동_문자를_뺀_32문자다() {
        assertThat(InviteCodes.ALPHABET).hasSize(32).doesNotContain("0", "O", "1", "I");
    }

    @Test
    void 만든_코드는_8자리이고_알파벳_안의_문자만_쓰며_겹치지_않는다() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String code = InviteCodes.generate();
            assertThat(code).hasSize(8).matches("[" + InviteCodes.ALPHABET + "]{8}");
            codes.add(code);
        }
        assertThat(codes).hasSize(1_000);
    }

    @ParameterizedTest
    @CsvSource({
            "K7MPQ2XA, K7MPQ2XA",
            "k7mpq2xa, K7MPQ2XA",
            "K7MP-Q2XA, K7MPQ2XA",
            "' K7MP Q2XA ', K7MPQ2XA",
    })
    void 대소문자_공백_하이픈은_무시한다(String input, String expected) {
        assertThat(InviteCodes.normalize(input)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "K7MPQ2X", "K7MPQ2XAB", "K7MPQ2X0", "K7MPQ2XO", "K7MPQ2X1", "K7MPQ2XI", "가나다라마바사아", "" })
    void 길이가_다르거나_허용되지_않은_문자가_있으면_코드가_아니다(String input) {
        assertThat(InviteCodes.normalize(input)).isEmpty();
    }
}
