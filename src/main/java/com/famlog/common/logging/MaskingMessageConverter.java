package com.famlog.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 텍스트 로그(local·test)의 %m / %msg / %message 를 마스킹한다. logback-spring.xml 에서 등록한다.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.mask(super.convert(event));
    }
}
