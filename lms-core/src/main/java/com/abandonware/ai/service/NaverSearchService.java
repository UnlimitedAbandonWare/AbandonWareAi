package com.abandonware.ai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * com.abandonware.* 네임스페이스용 얇은 래퍼.
 *
 * - 구현은 com.example.lms.service.NaverSearchService 를 재사용합니다.
 * - 이 래퍼는 "네트워크 비가용/실패" 상황에서 호출자가 죽지 않도록 fail-soft 합니다.
 */
@Service
public class NaverSearchService {
    private static final Pattern VERSION_PATTERN = Pattern.compile("v?\\d+(\\.\\d+)+");

    @Value("${naver.hedge.enabled:true}")
    private boolean hedgeEnabled;

    @Value("${naver.hedge.delay-ms:200}")
    private int hedgeDelayMs;

    @Value("${naver.search.timeout-ms:3000}") /* [ECO-FIX v3.0] 40000 -> 3000 (3초 컷) */
    private int timeoutMs;

    @Value("${naver.search.web-top-k:15}")
    private int webTopK;

    @Autowired(required = false)
    private com.example.lms.service.NaverSearchService delegate;

    public String summary() {
        return "hedge=" + hedgeEnabled + ", delay=" + hedgeDelayMs + "ms, timeout=" + timeoutMs + "ms, topK=" + webTopK;
    }

    public boolean isHedgeEnabled() {
        return hedgeEnabled;
    }

    public int getHedgeDelayMs() {
        return hedgeDelayMs;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getWebTopK() {
        return webTopK;
    }

    /**
     * com.example 구현을 재사용하여 web snippet 목록을 반환합니다.
     *
     * @param query 검색 질의
     * @param topK  0 이하이면 설정값(naver.search.web-top-k) 사용
     */
    public List<String> searchSnippets(String query, int topK) {
        int k = topK > 0 ? topK : this.webTopK;
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 간단한 가드: 버전 문자열이 있을 때도 그대로 검색은 하되, 내부적으로는 예외 없이 진행
        boolean hasVersion = VERSION_PATTERN.matcher(query).find();
        try {
            if (delegate != null) {
                // delegate는 내부적으로 timeout/hedge 등을 자체적으로 처리합니다.
                return delegate.searchSnippets(query, k);
            }
        } catch (Exception ignored) {
            // fail-soft
        }

        return Collections.emptyList();
    }

    public List<String> searchSnippets(String query) {
        return searchSnippets(query, this.webTopK);
    }
}
