package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GenericReverseGeocodingClientTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void coordinateHashDigestFailureRecordsRedactedBreadcrumb() {
        String hash = GenericReverseGeocodingClient.coordinateHash(37.5665d, 126.9780d, "missing-digest");

        assertEquals("sha256-unavailable", hash);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.reverseGeocode.coordinateHash.suppressed"));
        assertEquals("digest", TraceStore.get("agent.reverseGeocode.coordinateHash.suppressed.stage"));
        assertEquals("NoSuchAlgorithmException",
                TraceStore.get("agent.reverseGeocode.coordinateHash.suppressed.errorType"));
        assertEquals(Boolean.TRUE,
                TraceStore.get("agent.reverseGeocode.coordinateHash.suppressed.coordinatesProvided"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("37.5665"), rendered);
        assertFalse(rendered.contains("126.978"), rendered);
    }
}
