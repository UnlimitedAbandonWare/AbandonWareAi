package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RrfFusionTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void invalidEnvWeightFallsBackWithRedactedBreadcrumb() {
        String rawWeight = "raw private rrf weight token";

        double value = RrfFusion.parseEnvDouble("RRF_K", 60.0d, rawWeight);

        assertEquals(60.0d, value, 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.rrf.env.suppressed"));
        assertEquals("env.double", TraceStore.get("agent.rrf.env.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.rrf.env.suppressed.errorType"));
        assertEquals("RRF_K", TraceStore.get("agent.rrf.env.suppressed.name"));
        assertEquals(rawWeight.length(), TraceStore.get("agent.rrf.env.suppressed.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawWeight));
    }

    @Test
    void invalidUrlFallsBackWithRedactedBreadcrumb() {
        String rawUrl = "http://[::1/raw-secret-token";

        List<Map<String, Object>> out = RrfFusion.fuse(
                List.of(Map.of("id", "doc-1", "url", rawUrl, "score", 1.0d)),
                List.of());

        assertEquals(1, out.size());
        assertEquals(rawUrl, out.get(0).get("url"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.rrf.url.suppressed"));
        assertEquals("canonicalUrl", TraceStore.get("agent.rrf.url.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("agent.rrf.url.suppressed.errorType"));
        assertEquals(rawUrl.length(), TraceStore.get("agent.rrf.url.suppressed.urlLength"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains(rawUrl), rendered);
        assertFalse(rendered.contains("raw-secret-token"), rendered);
    }
}
