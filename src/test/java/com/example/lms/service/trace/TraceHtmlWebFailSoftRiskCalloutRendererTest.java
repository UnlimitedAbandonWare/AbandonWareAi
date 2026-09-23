package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraceHtmlWebFailSoftRiskCalloutRendererTest {

    @Test
    void renderReturnsEmptyWhenNoRiskSignalsExist() {
        assertEquals("", TraceHtmlWebFailSoftRiskCalloutRenderer.render(Map.of(
                "web.failsoft.minCitationsRescue.preflight.deficit", 0,
                "web.failsoft.rateLimitBackoff.max.remainingMs", 0,
                "web.failsoft.rateLimitBackoff.max.delayMs", 0,
                "web.failsoft.rateLimitBackoff.skipped.cooldown.count", 0,
                "web.failsoft.minCitationsRescue.attempted", false)));
    }

    @Test
    void renderBackoffProviderAndRescueSummary() {
        String rawTavilyReason = "private tavily skip api_key=secret should not render";
        String html = TraceHtmlWebFailSoftRiskCalloutRenderer.render(Map.ofEntries(
                Map.entry("web.failsoft.minCitationsRescue.preflight.deficit", 2),
                Map.entry("web.failsoft.minCitationsRescue.preflight.needed", 3),
                Map.entry("web.failsoft.minCitationsRescue.preflight.citeableCount", 1),
                Map.entry("web.failsoft.minCitationsRescue.preflight.eligible", false),
                Map.entry("web.failsoft.minCitationsRescue.preflight.blockReason", "rate-limit"),
                Map.entry("web.failsoft.minCitationsRescue.preflight.candidates.count", 4),
                Map.entry("web.failsoft.minCitationsRescue.preflight.candidates.top3",
                        List.of("alpha <query>", "beta")),
                Map.entry("web.failsoft.rateLimitBackoff.cooldownTradeoff.class", "COOLDOWN"),
                Map.entry("web.failsoft.rateLimitBackoff.skipped.cooldown.count", 1),
                Map.entry("web.failsoft.rateLimitBackoff.max.delayMs", 250),
                Map.entry("web.naver.skipped.reason", "rate-limit"),
                Map.entry("web.naver.providerDisabled", true),
                Map.entry("web.naver.disabledReason", "missing_key"),
                Map.entry("web.naver.rateLimited", true),
                Map.entry("web.naver.retryAfterMs", 1200),
                Map.entry("web.tavily.skipped.reason", rawTavilyReason),
                Map.entry("web.tavily.providerDisabled", true),
                Map.entry("web.tavily.disabledReason", "missing_tavily_api_key"),
                Map.entry("web.tavily.failureReason", "provider-disabled"),
                Map.entry("web.tavily.retryAfterMs", 900)));

        assertTrue(html.contains("Web FailSoft Risk"));
        assertTrue(html.contains("<b>Backoff</b>: COOLDOWN"));
        assertTrue(html.contains("<b>naver</b>: skip=rate-limit"));
        assertTrue(html.contains("disabled=true/missing_key"));
        assertTrue(html.contains("rateLimited=true"));
        assertTrue(html.contains("retryAfterMs=1200"));
        assertTrue(html.contains("<b>tavily</b>: skip=" + SafeRedactor.hashValue(rawTavilyReason)));
        assertTrue(html.contains("disabled=true/missing_tavily_api_key"));
        assertTrue(html.contains("failure=provider-disabled"));
        assertFalse(html.contains(rawTavilyReason));
        assertTrue(html.contains("MinCitations rescue"));
        assertTrue(html.contains("SKIP"));
        assertTrue(html.contains("deficit=2"));
        assertTrue(html.contains("preflight candidates: 4"));
        assertTrue(html.contains("alpha &lt;query&gt;"));
        assertFalse(html.contains("alpha <query>"));
    }

    @Test
    void renderProviderOnlyRiskWhenProviderIsSkippedWithoutBackoffSignals() {
        String rawReason = "disabled ownerToken=fixture api_key=fixture";
        String html = TraceHtmlWebFailSoftRiskCalloutRenderer.render(Map.of(
                "web.brave.skipped.reason", rawReason,
                "web.brave.providerDisabled", true,
                "web.brave.disabledReason", "missing_brave_api_key"));

        assertTrue(html.contains("Web FailSoft Risk"));
        assertTrue(html.contains("<b>brave</b>: skip=" + SafeRedactor.hashValue(rawReason)));
        assertTrue(html.contains("disabled=true/missing_brave_api_key"));
        assertFalse(html.contains(rawReason));
    }

    @Test
    void renderCandidateQueriesWithSecretMaskedPreview() {
        String rawCandidate = "private candidate api_key=hidden-secret Bearer " + "local-placeholder-token";
        String html = TraceHtmlWebFailSoftRiskCalloutRenderer.render(Map.ofEntries(
                Map.entry("web.failsoft.minCitationsRescue.preflight.deficit", 1),
                Map.entry("web.failsoft.minCitationsRescue.preflight.candidates.top3", List.of(rawCandidate))));

        assertTrue(html.contains("candidate queries"));
        assertFalse(html.contains("hidden-secret"), html);
        assertFalse(html.contains("local-placeholder-token"), html);
    }

    @Test
    void renderSuppressionStoresErrorTypeWithoutRawExceptionMessage() {
        TraceStore.clear();
        Map<String, Object> throwingMeta = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of(Map.entry("web.failsoft.minCitationsRescue.preflight.deficit", 1));
            }

            @Override
            public Object get(Object key) {
                throw new IllegalStateException("private failsoft ownerToken=raw-secret");
            }
        };

        String html = TraceHtmlWebFailSoftRiskCalloutRenderer.render(throwingMeta);

        assertEquals("", html);
        assertEquals(Boolean.TRUE, TraceStore.get("traceHtml.webFailSoftRisk.suppressed.render"));
        assertEquals("IllegalStateException", TraceStore.get("traceHtml.webFailSoftRisk.suppressed.render.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        TraceStore.clear();
    }
}
