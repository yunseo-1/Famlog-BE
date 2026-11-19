package com.famlog.place.kakao;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 카카오 지번 주소(address_name)에서 지역을 뽑는다 (사양서 4.4 PLC-02 시·도 → 시·군·구).
 * <ul>
 * <li>"서울 송파구 잠실동 40-1" → 서울 / 송파구 / 잠실동</li>
 * <li>"경기 수원시 영통구 이의동 1" → 경기 / 수원시 영통구 / 이의동 (일반구가 있는 시는 카카오 표기 그대로)</li>
 * <li>"세종특별자치시 한솔동 960" → 세종 / (없음) / 한솔동</li>
 * </ul>
 * 시·도는 카카오의 짧은 이름으로 맞춘다 (서울특별시·강원특별자치도 → 서울·강원).
 */
public record KakaoAddress(String region1, String region2, String region3) {

    private static final Map<String, String> REGION1 = Map.ofEntries(
            Map.entry("서울특별시", "서울"), Map.entry("부산광역시", "부산"), Map.entry("대구광역시", "대구"),
            Map.entry("인천광역시", "인천"), Map.entry("광주광역시", "광주"), Map.entry("대전광역시", "대전"),
            Map.entry("울산광역시", "울산"), Map.entry("세종특별자치시", "세종"), Map.entry("경기도", "경기"),
            Map.entry("강원도", "강원"), Map.entry("강원특별자치도", "강원"), Map.entry("충청북도", "충북"),
            Map.entry("충청남도", "충남"), Map.entry("전라북도", "전북"), Map.entry("전북특별자치도", "전북"),
            Map.entry("전라남도", "전남"), Map.entry("경상북도", "경북"), Map.entry("경상남도", "경남"),
            Map.entry("제주특별자치도", "제주"));

    /** 번지("40-1")·산 번지는 동 이름이 아니다 */
    private static final Pattern LOT = Pattern.compile("^(산?\\d.*|산)$");

    public static KakaoAddress parse(String addressName) {
        if (addressName == null || addressName.isBlank()) {
            throw new IllegalArgumentException("주소가 없어요.");
        }
        String[] tokens = addressName.strip().split("\\s+");
        String region1 = REGION1.getOrDefault(tokens[0], tokens[0]);
        int next = 1;
        String region2 = null;
        if (!region1.equals("세종") && next < tokens.length && isDistrict(tokens[next])) {
            region2 = tokens[next++];
            // 일반구: 수원시 영통구, 창원시 의창구
            if (region2.endsWith("시") && next < tokens.length && tokens[next].endsWith("구")) {
                region2 = region2 + " " + tokens[next++];
            }
        }
        String region3 = next < tokens.length && !LOT.matcher(tokens[next]).matches() ? tokens[next] : null;
        return new KakaoAddress(region1, region2, region3);
    }

    private static boolean isDistrict(String token) {
        return token.endsWith("시") || token.endsWith("군") || token.endsWith("구");
    }
}
