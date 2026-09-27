package com.example.lms.api;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicUrlSupportTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidPublicOriginLeavesRedactedTraceBreadcrumb() {
        String normalized = PublicUrlSupport.publicOriginUrl("https://%");

        assertEquals("https://%", normalized);
        assertEquals("public.origin", TraceStore.get("api.publicUrl.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("api.publicUrl.suppressed.errorType"));
        assertEquals(true, TraceStore.get("api.publicUrl.suppressed.public.origin"));
        assertEquals(9, TraceStore.get("api.publicUrl.suppressed.inputLength"));
        assertTrue(TraceStore.get("api.publicUrl.suppressed.inputHash") instanceof String);
        assertNull(TraceStore.get("api.publicUrl.suppressed.rawInput"));
    }
}
