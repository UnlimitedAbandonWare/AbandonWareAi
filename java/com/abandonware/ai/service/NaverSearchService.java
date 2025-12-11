package com.abandonware.ai.service;

import trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import java.util.regex.Pattern;

@Service
public class NaverSearchService {
    private static final Pattern VERSION_PATTERN = Pattern.compile("v?\\d+(\\.\\d+)+");

    @Autowired
    private GuardProfileProps guardProfileProps;


    @Value("${naver.hedge.enabled:true}")
    private boolean hedgeEnabled;

    @Value("${naver.hedge.delay-ms:200}")
    private int hedgeDelayMs;

    @Value("${naver.search.timeout-ms:3000}") /* [ECO-FIX v3.0] 40000 -> 3000 (3초 컷) */
    private int timeoutMs;

    @Value("${naver.search.web-top-k:15}")
    private int webTopK;

    public String summary() {
        return "hedge=" + hedgeEnabled + ", delay=" + hedgeDelayMs + "ms, timeout=" + timeoutMs + "ms, topK=" + webTopK;
    }
}

// PATCH_MARKER: NaverSearchService updated per latest spec.
