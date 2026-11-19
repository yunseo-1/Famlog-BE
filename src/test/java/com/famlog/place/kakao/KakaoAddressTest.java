package com.famlog.place.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 카카오 지번 주소에서 시·도 / 시·군·구 / 읍·면·동 (사양서 4.4 PLC-02). */
class KakaoAddressTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            서울 송파구 잠실동 40-1           | 서울 | 송파구          | 잠실동
            서울특별시 강남구 역삼동 1        | 서울 | 강남구          | 역삼동
            경기 수원시 영통구 이의동 1-1     | 경기 | 수원시 영통구   | 이의동
            경기 양평군 양평읍 양근리 1       | 경기 | 양평군          | 양평읍
            강원특별자치도 강릉시 교동 1      | 강원 | 강릉시          | 교동
            전북특별자치도 전주시 완산구 효자동1가 1 | 전북 | 전주시 완산구 | 효자동1가
            세종특별자치시 한솔동 960         | 세종 | -               | 한솔동
            제주특별자치도 제주시 산 12       | 제주 | 제주시          | -
            부산 해운대구                     | 부산 | 해운대구        | -
            """)
    void 시도는_짧은_이름으로_일반구는_시와_함께_세종은_시군구가_없다(String address, String region1, String region2,
            String region3) {
        assertThat(KakaoAddress.parse(address)).isEqualTo(new KakaoAddress(region1, region2, region3));
    }

    @Test
    void 주소가_없으면_뽑을_수_없다() {
        assertThatThrownBy(() -> KakaoAddress.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
