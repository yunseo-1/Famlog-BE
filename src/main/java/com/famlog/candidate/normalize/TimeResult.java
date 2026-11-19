package com.famlog.candidate.normalize;

import java.time.LocalTime;

/**
 * @param ampmInferred 오전·오후를 1~6시 오후 / 7~11시 오전 / 12시 낮 규칙으로 추정했다
 * @param nextDay      말한 날의 다음 날 시각이다 (자정, 밤 12시)
 */
public record TimeResult(LocalTime time, boolean ampmInferred, boolean nextDay) {
}
