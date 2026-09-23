package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextUtilsTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidDateRecencyFallbackLeavesTypeOnlyTrace() {
        double boost = TextUtils.recencyBoost("document date 2024-02-31 raw private date");

        assertEquals(0.5d, boost);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.text.recency.suppressed"));
        assertEquals("date.parseFallback", TraceStore.get("agent.text.recency.suppressed.stage"));
        assertEquals(1L, TraceStore.get("agent.text.recency.date.parseFallback.count"));
        assertEquals("invalid_date", TraceStore.get("agent.text.recency.date.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private date"));
    }

    @Test
    void sha1DigestFallbackLeavesTypeOnlyTrace() {
        String hash = TextUtils.sha1("raw private hash input", "NO_SUCH_DIGEST");

        assertEquals(Integer.toHexString("raw private hash input".hashCode()), hash);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.text.sha1.suppressed"));
        assertEquals("digest", TraceStore.get("agent.text.sha1.suppressed.stage"));
        assertEquals("NoSuchAlgorithmException", TraceStore.get("agent.text.sha1.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private hash input"));
    }
}
