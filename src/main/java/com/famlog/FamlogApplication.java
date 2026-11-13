package com.famlog;

import java.time.ZoneOffset;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FamlogApplication {

    public static void main(String[] args) {
        // 서버 내부 시간 계산은 UTC 기준. 응답 표시는 FamlogTimeModule이 +09:00으로 바꾼다 (사양서 10장, 11.1)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(FamlogApplication.class, args);
    }
}
