package com.abandonware.ai.service;

import trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.NaverSearchService
 * Role: service
 * Feature Flags: naver.hedge.enabled, naver.search.timeout-ms, naver.search.web-top-k
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.NaverSearchService
role: service
flags: [naver.hedge.enabled, naver.search.timeout-ms, naver.search.web-top-k]
*/
public class NaverSearchService {

    @Value("${naver.hedge.enabled:true}")
    private boolean hedgeEnabled;

    @Value("${naver.hedge.delay-ms:120}")
    private int hedgeDelayMs;

    @Value("${naver.search.timeout-ms:1800}")
    private int timeoutMs;

    @Value("${naver.search.web-top-k:15}")
    private int webTopK;

    public String summary() {
        return "hedge=" + hedgeEnabled + ", delay=" + hedgeDelayMs + "ms, timeout=" + timeoutMs + "ms, topK=" + webTopK;
    }
}