package com.example.lms.service.service.rag.fusion;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CanonicalizerTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidUrlFallbackRecordsSafeErrorType() {
        String rawUrl = "https://example .test/path?token=raw-secret-token";

        String fallback = Canonicalizer.canonicalUrl(rawUrl);

        assertEquals(rawUrl, fallback);
        assertEquals(Boolean.TRUE, TraceStore.get("rag.fusion.canonicalizer.suppressed.url"));
        assertEquals("invalid_url", TraceStore.get("rag.fusion.canonicalizer.suppressed.url.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawUrl));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret-token"));
    }
}
