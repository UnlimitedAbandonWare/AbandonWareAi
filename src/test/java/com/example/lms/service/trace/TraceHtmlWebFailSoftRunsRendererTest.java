package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraceHtmlWebFailSoftRunsRendererTest {

    @Test
    void appendDoesNothingWhenRunsMetadataIsMissing() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", "not-a-list"), shown);

        assertEquals("", html.toString());
        assertTrue(shown.isEmpty());
    }

    @Test
    void appendRendersSummaryAndEscapesRunFields() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", List.of(Map.of(
                "runId", "run<1>",
                "executedQuery", "alpha <query>",
                "outCount", 0,
                "officialOnly", true,
                "minCitations", 3,
                "starvationFallback", "cache <rescue>",
                "stageCountsSelectedFromOut", Map.of("NOFILTER_SAFE", 2),
                "candidates", List.of(Map.of(
                        "idx", 1,
                        "stage", "NOFILTER_SAFE",
                        "dropReason", "private candidate drop reason from user prompt",
                        "url", "https://example.test/path?<x>"))))), shown);

        String out = html.toString();
        assertTrue(out.contains("Web FailSoft Runs"));
        assertTrue(out.contains("runs=1"));
        assertTrue(out.contains("outZero=1"));
        assertTrue(out.contains("run&lt;1&gt;"));
        assertTrue(out.contains("alpha &lt;query&gt;"));
        assertTrue(out.contains("cache &lt;rescue&gt;"));
        assertTrue(out.contains("nofilterSafeRatio=0.00"));
        assertTrue(out.contains("cands=1"));
        assertTrue(out.contains("hash:"));
        assertFalse(out.contains("run<1>"));
        assertFalse(out.contains("alpha <query>"));
        assertFalse(out.contains("private candidate drop reason"));
        assertTrue(shown.contains("web.failsoft.runs"));
    }

    @Test
    void appendRendersLadderKpisInOptionsColumn() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        Map<String, Object> run = Map.of(
                "runId", "run-kpi",
                "outCount", 0,
                "cacheOnly.merged.count", 3,
                "tracePool.size", 4,
                "rescueMerge.used", true,
                "poolSafeEmpty", false,
                "vectorFallback.used", true,
                "vectorFallback.reason", "web_empty",
                "vectorFallback.effectiveTopK", 10);

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", List.of(run)), shown);

        String out = html.toString();
        assertTrue(out.contains("cacheMerged=3"), out);
        assertTrue(out.contains("tracePool=4"), out);
        assertTrue(out.contains("rescueMerge=true"), out);
        assertTrue(out.contains("poolSafeEmpty=false"), out);
        assertTrue(out.contains("vectorFallback=true/web_empty/10"), out);
    }

    @Test
    void appendTracesInvalidNofilterSafeCountWithStableErrorType() {
        TraceStore.clear();
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();
        String oversized = "99999999999999999999999999999999999999999999999999";

        Map<String, Object> run = Map.of(
                "runId", "run-overflow",
                "outCount", 1,
                "stageCountsSelected", "NOFILTER_SAFE=" + oversized);

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", List.of(run)), shown);

        assertTrue(html.toString().contains("nofilterSafeRatio=0.00"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceHtml.webFailSoftRuns.suppressed.nofilterSafeCount"));
        assertEquals("invalid_number",
                TraceStore.get("traceHtml.webFailSoftRuns.suppressed.nofilterSafeCount.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(oversized), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
    }

    @Test
    void appendMasksSensitiveRunAndCandidateFields() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();
        String bearer = "Bearer " + "local-placeholder-token";
        String rawApiValue = "hidden-secret";

        Map<String, Object> candidate = Map.of(
                "idx", 1,
                "tokenHits", "term api_key=" + rawApiValue,
                "negHits", "negative " + bearer,
                "rule", "rule " + bearer,
                "url", "https://example.test/search?api_key=" + rawApiValue);
        Map<String, Object> run = Map.of(
                "runId", "run-2",
                "executedQuery", "private query " + bearer + " api_key=" + rawApiValue,
                "outCount", 0,
                "candidates", List.of(candidate));

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", List.of(run)), shown);

        String out = html.toString();
        assertTrue(out.contains("Web FailSoft Runs"));
        assertFalse(out.contains("local-placeholder-token"), out);
        assertFalse(out.contains(rawApiValue), out);
    }

    @Test
    void appendMasksSensitiveRunAndCandidateLabels() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";

        Map<String, Object> candidate = Map.of(
                "idx", 1,
                "stage", "NOFILTER_SAFE " + bearer,
                "stageFinal", "FINAL " + queryKey,
                "baseStage", "BASE " + bearer,
                "cred", "HIGH " + queryKey,
                "selected", "true " + bearer);
        Map<String, Object> run = Map.of(
                "runId", "run " + bearer,
                "canonicalQuery", "canonical",
                "outCount", 0,
                "starvationFallback", "cache " + queryKey,
                "stageCountsSelected", "NOFILTER_SAFE=1 " + bearer,
                "candidates", List.of(candidate));

        TraceHtmlWebFailSoftRunsRenderer.append(html, Map.of("web.failsoft.runs", List.of(run)), shown);

        String out = html.toString();
        assertTrue(out.contains("Web FailSoft Runs"));
        assertFalse(out.contains(bearer), out);
        assertFalse(out.contains(queryKey), out);
    }
}
