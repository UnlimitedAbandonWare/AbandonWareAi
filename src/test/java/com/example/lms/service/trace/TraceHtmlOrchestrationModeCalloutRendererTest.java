package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.lms.trace.SafeRedactor;

class TraceHtmlOrchestrationModeCalloutRendererTest {

    @Test
    void renderReturnsEmptyForMissingMetadata() {
        assertEquals("", TraceHtmlOrchestrationModeCalloutRenderer.render(Map.of()));
    }

    @Test
    void renderModeFlagsReasonAndThumbnailHits() {
        String html = TraceHtmlOrchestrationModeCalloutRenderer.render(Map.of(
                "orch.mode", "BYPASS",
                "orch.bypass", true,
                "orch.webRateLimited", "true",
                "orch.auxLlmDown", false,
                "orch.reasons", List.of("rate-limit", "query fallback"),
                "uaw.thumb.recall.hits", "3",
                "uaw.thumb.recall.entities", "Alpha <Beta>"));

        assertTrue(html.contains("<b>Mode</b>: BYPASS"));
        assertTrue(html.contains("border-left:6px solid #f90"));
        assertTrue(html.contains("STRIKE=false"));
        assertTrue(html.contains("BYPASS=true"));
        assertTrue(html.contains("webRateLimited=true"));
        assertTrue(html.contains("<b>Why</b>:"));
        assertTrue(html.contains("recall hits=3"));
        assertTrue(html.contains("Alpha &lt;Beta&gt;"));
        assertFalse(html.contains("Alpha <Beta>"));
    }

    @Test
    void renderEscapesModeAndPrefersExplicitReason() {
        String html = TraceHtmlOrchestrationModeCalloutRenderer.render(Map.of(
                "orch.modeLabel", "STRIKE<script>",
                "orch.strike", true,
                "orch.reason", "manual override",
                "orch.reasons", List.of("ignored")));

        assertTrue(html.contains("STRIKE&lt;script&gt;"));
        assertTrue(html.contains("border-left:6px solid #d33"));
        assertTrue(html.contains(SafeRedactor.traceLabelOrFallback("manual override", "reason")));
        assertFalse(html.contains("ignored"));
        assertFalse(html.contains("STRIKE<script>"));
    }

    @Test
    void renderShowsWhyForRelaxedVectorFiltersWithoutExplicitReason() {
        String html = TraceHtmlOrchestrationModeCalloutRenderer.render(Map.of(
                "orch.mode", "FULLSCALE",
                "plan.id", "rulebreak.v1",
                "vector.scopeFilter.relaxed", true,
                "vector.docTypeFilter.relaxed", "true"));

        assertTrue(html.contains("<b>Mode</b>: FULLSCALE"));
        assertTrue(html.contains("<b>Why</b>:"));
        assertTrue(html.contains("vector_filter_relaxed"));
        assertTrue(html.contains("scope_filter"));
        assertTrue(html.contains("doc_type_filter"));
    }

    @Test
    void renderMasksSensitiveThumbnailEntities() {
        String bearer = "Bearer " + "local-placeholder-token";
        String html = TraceHtmlOrchestrationModeCalloutRenderer.render(Map.of(
                "uaw.thumb.recall.hits", "1",
                "uaw.thumb.recall.entities", "Alpha " + bearer));

        assertTrue(html.contains("recall hits=1"));
        assertFalse(html.contains("local-placeholder-token"), html);
    }
}
