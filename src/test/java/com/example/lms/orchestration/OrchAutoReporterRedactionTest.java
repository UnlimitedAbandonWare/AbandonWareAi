package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchAutoReporterRedactionTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void textRenderingLivesOutsideAutoReporterLargeFile() throws Exception {
        Path reporterPath = Path.of("main/java/com/example/lms/orchestration/OrchAutoReporter.java");
        Path rendererPath = Path.of("main/java/com/example/lms/orchestration/OrchAutoReportTextRenderer.java");

        String reporter = Files.readString(reporterPath);

        assertTrue(Files.exists(rendererPath), "text renderer should reduce OrchAutoReporter file size");
        String renderer = Files.readString(rendererPath);
        assertTrue(reporter.contains("return OrchAutoReportTextRenderer.renderText(VERSION, report);"));
        assertFalse(reporter.contains("StringBuilder sb = new StringBuilder();\n        sb.append(\"# Orchestration Auto Report"));
        assertTrue(renderer.contains("final class OrchAutoReportTextRenderer"));
        assertTrue(renderer.contains("static String renderText(String version, Map<String, Object> report)"));
    }

    @Test
    void reportAndRenderedTextMaskSecretLikeReasonsAndBreakerErrors() {
        String secret = "Bearer " + "unit-test-orch-auto-reporter-secret-token";
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("orch.reason", "aux failed api_key=" + secret);
        trace.put("nightmare.breaker.openKind", Map.of("query-transformer:runLLM", "CONFIG"));
        trace.put("nightmare.breaker.openErrMsg", Map.of("query-transformer:runLLM", "provider failed " + secret));
        trace.put("irregularity.events", List.of(Map.of(
                "reason", "bump reason " + secret,
                "delta", 0.25,
                "score", 0.8,
                "ts", "2026-06-05T00:00:00Z")));

        Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
        String rendered = report + "\n" + OrchAutoReporter.renderText(report);

        assertFalse(rendered.contains(secret));
        assertTrue(rendered.contains("redacted") || rendered.contains("*"));
    }

    @Test
    void reportAndRenderedTextHashFreeTextReasonsAndBreakerErrors() {
        String rawReason = "private orchestration reason about user query";
        String rawBreaker = "breaker opened after private customer query text";
        String rawIrregularity = "irregularity bump copied private prompt wording";
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("orch.reason", rawReason);
        trace.put("nightmare.breaker.openKind", Map.of("query-transformer:runLLM", "TIMEOUT"));
        trace.put("nightmare.breaker.openErrMsg", Map.of("query-transformer:runLLM", rawBreaker));
        trace.put("irregularity.events", List.of(Map.of(
                "reason", rawIrregularity,
                "delta", 0.25,
                "score", 0.8,
                "ts", "2026-06-05T00:00:00Z")));

        Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
        String rendered = report + "\n" + OrchAutoReporter.renderText(report);

        assertFalse(rendered.contains(rawReason));
        assertFalse(rendered.contains(rawBreaker));
        assertFalse(rendered.contains(rawIrregularity));
        assertTrue(rendered.contains("hash:"));
    }

    @Test
    void ablationFactorDiagnosticsHashFreeTextValues() {
        String rawFactor = "private ablation factor copied raw prompt wording";
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("orch.debug.ablation.bypass", List.of(Map.of(
                "factor", rawFactor,
                "deltaProb", 0.42,
                "value", 1.0)));

        Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
        String rendered = report + "\n" + OrchAutoReporter.renderText(report);

        assertFalse(rendered.contains(rawFactor));
        assertTrue(rendered.contains("hash:"));
    }

    @Test
    void tavilyAndSerpApiIrregularityReasonsStayInWebPipelineGroup() {
        for (String reason : List.of("tavily provider await_timeout", "serpapi provider rate_limit")) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("irregularity.events", List.of(Map.of(
                    "reason", reason,
                    "delta", 0.25,
                    "score", 0.8,
                    "ts", "2026-06-05T00:00:00Z")));

            Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
            String rendered = report + "\n" + OrchAutoReporter.renderText(report);

            assertTrue(rendered.contains("WEB_PIPELINE_FAILURE"), reason);
            assertTrue(rendered.contains("Web pipeline failure"), reason);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerCorrelationKeepsSerpApiAndTavilyBreakerKeys() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("web.await.events", List.of(
                Map.of("engine", "SerpApi", "nonOk", true, "httpStatus", 429),
                Map.of("engine", "Tavily", "nonOk", true, "cause", "timeout")));
        trace.put("nightmare.rateLimit.once.websearch:serpapi", true);
        trace.put("nightmare.rateLimit.once.websearch:tavily", true);

        Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("providerCorrelation");

        assertTrue(rows.stream().anyMatch(row ->
                "SerpApi".equals(row.get("engine"))
                        && "websearch:serpapi".equals(row.get("breakerKey"))
                        && ((Number) row.get("rateLimitSignals")).longValue() == 1L), String.valueOf(rows));
        assertTrue(rows.stream().anyMatch(row ->
                "Tavily".equals(row.get("engine"))
                        && "websearch:tavily".equals(row.get("breakerKey"))
                        && ((Number) row.get("rateLimitSignals")).longValue() == 1L), String.valueOf(rows));
    }

    @Test
    void providerCorrelationMasksSecretShapedProviderLabels() {
        String secretEngine = "sk-" + "providerengine01234567890";
        String secretStatus = "pcsk_" + "providerstatus01234567890";
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("web.await.events", List.of(Map.of(
                "engine", secretEngine,
                "nonOk", true,
                "httpStatus", 429,
                "cause", secretStatus)));
        trace.put("web.provider.events", List.of(Map.of(
                "engine", secretEngine,
                "status", secretStatus,
                "httpStatus", 429)));
        trace.put("nightmare.breaker.openKind", Map.of("websearch:" + secretEngine, secretStatus));
        trace.put("nightmare.breaker.openErrMsg", Map.of("websearch:" + secretEngine, secretStatus));

        Map<String, Object> report = OrchAutoReporter.buildFromMap(trace);
        String rendered = report + "\n" + OrchAutoReporter.renderText(report);

        assertFalse(rendered.contains(secretEngine), rendered);
        assertFalse(rendered.contains(secretStatus), rendered);
        assertTrue(rendered.contains("hash:"), rendered);
    }

    @Test
    void numericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/orchestration/OrchAutoReporter.java"));

        assertParserCatchNarrowed(source, "private static Integer intOrNull");
        assertParserCatchNarrowed(source, "private static double dbl");
        assertParserCatchNarrowed(source, "private static Double dblOrNull");
        assertParserCatchNarrowed(source, "private static double parseDouble");
    }

    @Test
    void reporterFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/orchestration/OrchAutoReporter.java"));
        String traceHelper = Files.readString(Path.of("main/java/com/example/lms/orchestration/OrchAutoReportTrace.java"));

        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"emit_to_trace\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"build_from\", t);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"ablation_delta_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"ablation_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"int_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"irregularity_events_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"double_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"double_or_null_parse\", ignore);"));
        assertTrue(source.contains("OrchAutoReportTrace.traceSuppressed(\"parse_double\", ignore);"));
        assertTrue(traceHelper.contains("TraceStore.put(\"orch.autoReport.suppressed.\" + safeStage, true);"));
    }

    @Test
    void reporterNumericSuppressionUsesStableInvalidNumberLabel() {
        OrchAutoReportTrace.traceSuppressed("int_parse", new NumberFormatException("private count"));

        assertEquals(Boolean.TRUE, TraceStore.get("orch.autoReport.suppressed.int_parse"));
        assertEquals("invalid_number", TraceStore.get("orch.autoReport.suppressed.int_parse.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);

        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
    }
}
