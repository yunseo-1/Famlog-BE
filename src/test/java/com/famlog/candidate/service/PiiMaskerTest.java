package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** LLM 전송 전 마스킹 (사양서 6.2 ①, 8.7): 전화·계좌·카드·주민번호·이메일. 날짜·시간·금액은 그대로. */
class PiiMaskerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "내 번호 010-1234-5678 이야|내 번호 [전화번호] 이야",
            "01012345678로 전화해|[전화번호]로 전화해",
            "집 02-345-6789|집 [전화번호]",
            "+82 10-1234-5678|[전화번호]",
            "고객센터 1588-1234|고객센터 [전화번호]",
            "주민번호 900101-1234567|주민번호 [주민등록번호]",
            "카드 1234-5678-9012-3456 써|카드 [카드번호] 써",
            "1234 5678 9012 3456|[카드번호]",
            "국민 123-45-6789-012 로 보내|국민 [계좌번호] 로 보내",
            "신한 110-123-456789|신한 [계좌번호]",
            "3333012345678 카카오뱅크|[계좌번호] 카카오뱅크",
            "메일 mom.kim@example.com 으로|메일 [이메일] 으로"
    })
    void 가린다(String text, String expected) {
        assertThat(PiiMasker.mask(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "이번 토요일 11시에 할머니 댁 가자", "15일까지", "2026-11-14 오전 10:30", "11/14 7시", "3만원 보내 줘",
            "50,000원", "2026년 12월 31일", "버스 7016번", "20분 뒤에"
    })
    void 날짜_시간_금액은_그대로_둔다(String text) {
        assertThat(PiiMasker.mask(text)).isEqualTo(text);
    }
}
