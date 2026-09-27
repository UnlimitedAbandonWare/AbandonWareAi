package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriadicDebugAdjudicationPageContractTest {

    @Test
    void debugEventsPageUsesExplicitPostAndTextOnlyRendering() throws Exception {
        String html = Files.readString(
                Path.of("main/resources/templates/debug-events.html"),
                StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"btnTriadicAdjudicate\""));
        assertTrue(html.contains("id=\"triadicCandidateSummary\""));
        assertTrue(html.contains("id=\"triadicTargetFiles\""));
        assertTrue(html.contains("id=\"triadicDiffSha256\""));
        assertTrue(html.contains("/api/diagnostics/debug/triadic-adjudication"));
        assertTrue(html.contains("method: 'POST'"));
        assertTrue(html.contains("'Content-Type': 'application/json'"));
        assertTrue(html.contains("body: JSON.stringify(candidate)"));
        assertTrue(html.contains("csrfHeaders()"));
        assertTrue(html.contains("document.querySelector('meta[name=\"_csrf\"]')"));
        assertTrue(html.contains("document.querySelector('meta[name=\"_csrf_header\"]')"));
        assertTrue(html.contains("triadicDecision.textContent"));
        assertTrue(html.contains("triadicReasonCode.textContent"));
        assertFalse(html.contains("triadicDecision.innerHTML"));
        assertFalse(html.contains("triadicReasonCode.innerHTML"));
    }
}
