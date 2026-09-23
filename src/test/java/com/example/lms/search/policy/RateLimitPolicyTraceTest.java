package com.example.lms.search.policy;

import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPolicyTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidRateLimitHeadersLeaveTypeOnlyTrace() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-rate-limit-remaining", "raw private remaining token");
        headers.add("Retry-After", "raw private retry token");

        RateLimitPolicy policy = new RateLimitPolicy();
        policy.updateFromHeaders(headers);

        assertEquals(0L, policy.retryAfterMs());
        assertEquals(1L, TraceStore.get("rateLimit.header.remaining.parseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("rateLimit.header.remaining.parseFallback.errorType"));
        assertEquals(1L, TraceStore.get("rateLimit.header.retryAfter.secondsParseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("rateLimit.header.retryAfter.secondsParseFallback.errorType"));
        assertEquals(1L, TraceStore.get("rateLimit.header.retryAfter.dateParseFallback.count"));
        assertEquals("invalid_date", TraceStore.get("rateLimit.header.retryAfter.dateParseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private remaining token"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private retry token"));
    }

    @Test
    void retryAfterSecondsCapAppliesBeforeMultiplicationOverflow() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", Long.toString(Long.MAX_VALUE));
        RateLimitPolicy policy = new RateLimitPolicy();

        policy.updateFromHeaders(headers);

        assertEquals(60_000L, policy.retryAfterMs());
    }

    @Test
    void parseFallbackCatchesUseScannerVisibleSuppressionHelper() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/RateLimitPolicy.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"retryAfter.secondsParseFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"retryAfter.dateParseFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(headerStage(n), ex);"));
    }
}
