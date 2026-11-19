package com.famlog.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.famlog.place.kakao.KakaoLocalClient;

/**
 * 실제 카카오 로컬 API 대신 검색어별로 정해 둔 결과를 준다. 컨텍스트를 테스트끼리 나눠 쓰므로 검색어·장소 id 는 테스트마다 새로 만든다.
 * 호출 횟수와 마지막 호출에 좌표가 있었는지를 기록한다 (캐시 확인용).
 */
public class FakeKakaoLocalClient implements KakaoLocalClient {

    private final Map<String, SearchResult> results = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Boolean> locatedCalls = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    /** 이 검색어의 1쪽 결과 (end = 마지막 쪽인지) */
    public void register(String query, boolean end, Document... documents) {
        results.put(query, new SearchResult(List.of(documents), end));
    }

    /** 이 검색어는 카카오 장애처럼 예외를 던진다 */
    public void fail(String query) {
        failing.add(query);
    }

    public int callCount(String query) {
        AtomicInteger count = calls.get(query);
        return count == null ? 0 : count.get();
    }

    /** 이 검색어로 좌표를 넣어 부른 적이 있는지 */
    public boolean calledWithLocation(String query) {
        return locatedCalls.getOrDefault(query, false);
    }

    @Override
    public SearchResult search(String query, Double x, Double y, int page) {
        calls.computeIfAbsent(query, key -> new AtomicInteger()).incrementAndGet();
        if (x != null) {
            locatedCalls.put(query, true);
        }
        if (failing.contains(query)) {
            throw new IllegalStateException("카카오 장애");
        }
        SearchResult result = results.get(query);
        if (result == null || page > 1) {
            return new SearchResult(List.of(), true);
        }
        return result;
    }
}
