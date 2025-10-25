package com.abandonware.ai.service;

import trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
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