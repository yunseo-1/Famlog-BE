package com.famlog.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 문자열에서 민감 정보를 가린다 (사양서 9.8: 메시지 본문·토큰·좌표·출생연도월 로그 금지).
 * 마스킹은 최후의 방어선이다. 애초에 이런 값을 로그에 넘기지 않는 것이 원칙이다.
 * 애매하면 더 많이 가리는 쪽을 택한다.
 */
public final class LogMasker {

    static final String MASK = "[MASKED]";

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");

    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

    /** key=value, key: value, "key":"value" 형태. 따옴표 값은 끝 따옴표까지, 아니면 구분자(, } ] & 줄바꿈) 전까지. */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\"?\\b(?:access_?token|refresh_?token|id_?token|fcm_?token|token|password|secret"
                    + "|authorization|body|birth_?ym|lat|lng|latitude|longitude)\\b\"?\\s*[:=]\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]&\\r\\n]+)");

    private LogMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = BEARER.matcher(text).replaceAll("Bearer " + MASK);
        masked = JWT.matcher(masked).replaceAll(MASK);
        Matcher matcher = SENSITIVE_FIELD.matcher(masked);
        StringBuilder out = new StringBuilder(masked.length());
        while (matcher.find()) {
            String value = matcher.group(2);
            String replacement = value.startsWith("\"") ? "\"" + MASK + "\"" : MASK;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
