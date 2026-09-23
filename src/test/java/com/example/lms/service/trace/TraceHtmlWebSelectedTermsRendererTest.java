package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraceHtmlWebSelectedTermsRendererTest {

    @Test
    void appendDoesNothingWhenNoSelectedTermMetadataExists() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebSelectedTermsRenderer.append(html, Map.of("other", "value"), shown);

        assertEquals("", html.toString());
        assertTrue(shown.isEmpty());
    }

    @Test
    void appendFallbackRowsForNonStructuredSelectedTerms() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebSelectedTermsRenderer.append(html, Map.of(
                "web.effectiveQuery", "alpha <query>",
                "web.selectedTerms.summary", "summary <tag>",
                "web.selectedTerms", "flat <tokens>",
                "web.selectedTerms.applied", "applied <tokens>"), shown);

        String out = html.toString();
        assertTrue(out.contains("Web Debug"));
        assertTrue(out.contains("web.effectiveQuery"));
        assertTrue(out.contains("summary &lt;tag&gt;"));
        assertTrue(out.contains("flat &lt;tokens&gt;"));
        assertTrue(out.contains("applied &lt;tokens&gt;"));
        assertFalse(out.contains("alpha <query>"));
        assertTrue(shown.contains("web.effectiveQuery"));
        assertTrue(shown.contains("web.selectedTerms.summary"));
        assertTrue(shown.contains("web.selectedTerms"));
        assertTrue(shown.contains("web.selectedTerms.applied"));
    }

    @Test
    void appendStructuredSelectedTermsWithTokenLimitsAndEscaping() {
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("domainProfile", "official <only>");
        selected.put("counts", Map.of("exact", 13, "negative", 1));
        selected.put("samples", Map.of(
                "exact", List.of("one", "two", "three", "four", "five", "six",
                        "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen"),
                "negative", List.of("bad <token>")));

        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();
        TraceHtmlWebSelectedTermsRenderer.append(html, Map.of(
                "web.effectiveQuery", "where is <secret>",
                "web.effectiveQuery.hash12", "hash-present",
                "web.effectiveQuery.len", 17,
                "web.selectedTerms.summary", "summary <safe>",
                "web.selectedTerms", selected,
                "web.selectedTerms.rules", List.of("rule <one>", "rule <two>")), shown);

        String out = html.toString();
        assertTrue(out.contains("trace-selected-terms"));
        assertTrue(out.contains("effectiveQueryHash12: hash-present len=17"));
        assertTrue(out.contains("summary: summary &lt;safe&gt;"));
        assertTrue(out.contains("official &lt;only&gt;"));
        assertTrue(out.contains("exact (n=13)"));
        assertTrue(out.contains("bad &lt;token&gt;"));
        assertTrue(out.contains("twelve"));
        assertFalse(out.contains("thirteen"));
        assertTrue(out.contains("rules / evidence (2)"));
        assertTrue(out.contains("rule &lt;one&gt;"));
        assertFalse(out.contains("where is <secret>"));
        assertTrue(shown.contains("web.effectiveQuery"));
        assertTrue(shown.contains("web.selectedTerms.summary"));
        assertTrue(shown.contains("web.selectedTerms.rules"));
        assertTrue(shown.contains("web.selectedTerms"));
    }

    @Test
    void appendMasksSensitiveSelectedTermSamplesAndRules() {
        String bearer = "Bearer " + "local-placeholder-token";
        String apiValue = "hidden-secret";
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("domainProfile", "official api_key=" + apiValue);
        selected.put("samples", Map.of(
                "exact", List.of("term " + bearer),
                "negative", List.of("neg api_key=" + apiValue)));

        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();
        TraceHtmlWebSelectedTermsRenderer.append(html, Map.of(
                "web.selectedTerms.summary", "summary " + bearer,
                "web.selectedTerms", selected,
                "web.selectedTerms.rules", List.of("rule api_key=" + apiValue)), shown);

        String out = html.toString();
        assertTrue(out.contains("trace-selected-terms"));
        assertFalse(out.contains("local-placeholder-token"), out);
        assertFalse(out.contains(apiValue), out);
    }
}
