package com.famlog.candidate.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 시각 정규화 (사양서 6.2 ② 오전/오후 생략): 1~6시 → 오후, 7~11시 → 오전, 12시 → 낮 12시 (모두 추정).
 * 오전·오후·아침·저녁·밤·새벽·점심 단서가 시간 표현이나 같은 메시지에 있으면 따른다 (추정 아님).
 * 기대값: HH:mm, 추정이면 끝에 "~", 다음 날로 넘어가면 앞에 "+1 ", 해석 못 하면 UNKNOWN.
 */
class TimeNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" / 단서 \"{1}\" → {2}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // 오전·오후 생략 (추정)
            "11시|-|11:00~",
            "3시|-|15:00~",
            "6시|-|18:00~",
            "7시|-|07:00~",
            "12시|-|12:00~",
            "11시 반|-|11:30~",
            "3시 20분|-|15:20~",
            "7:30|-|07:30~",
            "다섯 시|-|17:00~",
            "열한 시 반|-|11:30~",
            // 단서가 시간 표현에 있음
            "오전 11시|-|11:00",
            "오후 3시|-|15:00",
            "저녁 6시|-|18:00",
            "아침 6시|-|06:00",
            "밤 9시|-|21:00",
            "새벽 2시|-|02:00",
            "점심 1시|-|13:00",
            "15시|-|15:00",
            "19:30|-|19:30",
            "정오|-|12:00",
            // 단서가 같은 메시지에 있음 (6.3 예시 5)
            "6시|다음 주 토요일 6시에 할머니댁 저녁|18:00",
            "7시|내일 7시에 저녁 먹자|19:00",
            // 자정은 다음 날 00:00, 없는 시각
            "자정|-|+1 00:00",
            "25시|-|UNKNOWN"
    })
    void 시각(String text, String clue, String expected) {
        assertThat(describe(TimeNormalizer.resolve(text, clue))).isEqualTo(expected);
    }

    static String describe(Optional<TimeResult> result) {
        return result.map(r -> (r.nextDay() ? "+1 " : "") + r.time() + (r.ampmInferred() ? "~" : ""))
                .orElse("UNKNOWN");
    }
}
