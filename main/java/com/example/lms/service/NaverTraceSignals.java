package com.example.lms.service;

import com.example.lms.search.TraceStore;

public final class NaverTraceSignals {

    private static final String BREAKER_OPEN_REASON = "breaker_open_or_half_open";

    private NaverTraceSignals() {
    }

    public static void traceBreakerOpen(long remainingMs) {
        long safeRemainingMs = Math.max(0L, remainingMs);
        TraceStore.put("web.naver.skippedByBreaker", true);
        TraceStore.put("web.naver.skipped", true);
        TraceStore.put("web.naver.skipped.reason", BREAKER_OPEN_REASON);
        TraceStore.put("web.naver.breakerOpen", true);
        TraceStore.put("web.naver.breaker.remainingMs", safeRemainingMs);
        TraceStore.putIfAbsent("web.naver.failureReason", BREAKER_OPEN_REASON);
    }
}
