package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorDiagnosticsPageTemplateTest {

    @Test
    void runtimeSummaryShowsBreakerAndVectorStoreScalarsWithoutHtmlInjection() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/vector-diagnostics.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"nightmareBreakerBadge\""));
        assertTrue(html.contains("id=\"nightmareBreakerState\""));
        assertTrue(html.contains("id=\"nightmareBreakerOpenCount\""));
        assertTrue(html.contains("id=\"vectorStoreBadge\""));
        assertTrue(html.contains("id=\"vectorStoreBuffer\""));
        assertTrue(html.contains("const breaker = (r && r.nightmareBreaker) ? r.nightmareBreaker : {};"));
        assertTrue(html.contains("const vectorStore = (r && r.vectorStore) ? r.vectorStore : {};"));
        assertTrue(html.contains("$('nightmareBreakerState').textContent"));
        assertTrue(html.contains("$('vectorStoreBuffer').textContent"));
        assertFalse(html.contains("innerHTML"));
        assertFalse(html.contains("insertAdjacentHTML"));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("ownerToken"));
        assertFalse(html.contains("apiKey"));
        assertFalse(html.contains("clientSecret"));
    }

    @Test
    void vectorSummaryShowsUpstashModeWithoutRawCredentialFields() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/vector-diagnostics.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"upstashBadge\""));
        assertTrue(html.contains("id=\"upstashMode\""));
        assertTrue(html.contains("id=\"upstashNamespace\""));
        assertTrue(html.contains("id=\"upstashHost\""));
        assertTrue(html.contains("const upstash = (vector && vector.upstash) ? vector.upstash : {};"));
        assertTrue(html.contains("$('upstashMode').textContent"));
        assertTrue(html.contains("$('upstashNamespace').textContent"));
        assertTrue(html.contains("$('upstashHost').textContent"));
        assertFalse(html.contains("rawQuery"));
        assertFalse(html.contains("endpointHost"));
    }
}
