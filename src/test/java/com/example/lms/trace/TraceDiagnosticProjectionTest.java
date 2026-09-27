package com.example.lms.trace;

import com.example.lms.service.trace.TraceHtmlBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceDiagnosticProjectionTest {
    private static Map<String, Object> diagnostics() {
        return Map.ofEntries(
                Map.entry("prompt.historyRendered", true),
                Map.entry("prompt.contextInjected.delivered", true),
                Map.entry("prompt.events.webCount", 4),
                Map.entry("llm.call.approxInputTokens", 128),
                Map.entry("llm.ollamaNative.maxTokens", 256L),
                Map.entry("memory.session.tokenEstimate", 64),
                Map.entry("llm.gateway.openai.tokenParamRetry", false));
    }

    @Test
    void exactDiagnosticScalarsKeepTypesAndAreIdempotent() {
        diagnostics().forEach((key, value) -> {
            Object once = SafeRedactor.diagnosticValue(key, value);
            assertEquals(value, once, key);
            assertEquals(value.getClass(), once.getClass(), key);
            assertEquals(once, SafeRedactor.diagnosticValue(key, once), key);
        });
    }

    @Test
    void nestedPromptCountsRemainTyped() {
        Object input = List.of(Map.of("webCount", 4, "ragCount", 2,
                "memoryPresent", true, "seq", 1));
        Object safe = SafeRedactor.diagnosticValue("prompt.events", input);
        assertEquals(input, safe);
        assertEquals(safe, SafeRedactor.diagnosticValue("prompt.events", safe));
    }

    @Test
    void standardEventLabelsSurviveRepeatedProjection() {
        Object input = List.of(Map.of("phase", "search", "step", "rerank",
                "stage", "retrieval", "status", "OK",
                "output", Map.of("selectedCount", 3)));
        Object safe = SafeRedactor.diagnosticValue("orch.events.v1", input);
        assertEquals(input, safe);
        assertEquals(safe, SafeRedactor.diagnosticValue("orch.events.v1", safe));
    }

    @Test
    void producedSummariesAreStableButUntrustedSummaryShapesAreNotTrusted() {
        Object safe = SafeRedactor.diagnosticValue("prompt.raw", "synthetic private prompt");
        assertEquals(safe, SafeRedactor.diagnosticValue("prompt.raw", safe));
        Map<String, Object> forged = Map.of("present", true, "len", 3,
                "hash12", "private-value-not-a-hash", "host", "private-host");
        String rejected = String.valueOf(SafeRedactor.diagnosticValue("prompt.raw", forged));
        assertFalse(rejected.contains("private-value-not-a-hash"));
        assertFalse(rejected.contains("private-host"));
    }

    @Test
    void registryDoesNotAllowWrongTypesSuffixesOrSensitiveContent() {
        for (String key : diagnostics().keySet()) {
            String raw = "synthetic private raw content";
            assertNotEquals(raw, SafeRedactor.diagnosticValue(key, raw), key);
            assertNotEquals(Map.of("present", true, "len", 4, "hash12", raw),
                    SafeRedactor.diagnosticValue(key, Map.of("present", true, "len", 4, "hash12", raw)));
        }
        for (String key : List.of("ownerToken", "clientToken", "apiKey", "authorization",
                "custom.maxTokens", "prefix.llm.call.approxInputTokens")) {
            assertEquals("(redacted)", SafeRedactor.diagnosticValue(key, 42), key);
        }
        assertNotEquals(true, SafeRedactor.diagnosticValue("prompt.unregisteredFlag", true));
        assertNotEquals(42, SafeRedactor.diagnosticValue("prompt.historyRendered", 42));
        String uri = "https://synthetic-user:synthetic-pass@example.test/path?private=hidden";
        assertFalse(String.valueOf(SafeRedactor.diagnosticValue("url", uri)).contains("synthetic-pass"));
        assertFalse(String.valueOf(SafeRedactor.diagnosticValue("prompt.raw", "synthetic private prompt"))
                .contains("synthetic private prompt"));
    }

    @Test
    void snapshotAndHtmlKeepSameLabelsAndScalarTypesWithoutGeneration() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("traceHtmlBuilder", new TraceHtmlBuilder(null));
        TraceSnapshotStore store = new TraceSnapshotStore(beans.getBeanProvider(TraceHtmlBuilder.class));
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "htmlEnabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "maxValueLen", 1000);
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "httpStatusMin", 400);
        ReflectionTestUtils.setField(store, "captureHttpOnDebug", true);
        ReflectionTestUtils.setField(store, "captureHttpOnMl", true);
        ReflectionTestUtils.setField(store, "captureHttpOnOrch", true);
        ReflectionTestUtils.setField(store, "captureHttpOnException", true);
        ReflectionTestUtils.setField(store, "htmlMaxLen", 60_000);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        Map<String, Object> input = new LinkedHashMap<>(diagnostics());
        input.put("orch.events.v1", List.of(Map.of("phase", "search", "step", "rerank",
                "stage", "retrieval", "status", "OK", "kind", "retrieval",
                "output", Map.of("selectedCount", 3))));
        input.put("prompt.raw", "synthetic private prompt");
        String id = store.captureCustom("unit_test", "POST", "/api/chat", 200, null, input, null);
        assertNotNull(id);
        var snapshot = store.get(id).orElseThrow();
        diagnostics().forEach((key, value) -> {
            assertEquals(value, snapshot.trace().get(key), key);
            assertTrue(snapshot.html().contains(key), key);
        });
        var htmlMeta = (Map<?, ?>) ReflectionTestUtils.invokeMethod(TraceHtmlBuilder.class,
                "sanitizeMeta", snapshot.trace());
        diagnostics().forEach((key, value) -> assertEquals(value, htmlMeta.get(key), key));
        assertTrue(snapshot.html().contains("search"));
        assertFalse(snapshot.html().contains("synthetic private prompt"));
        // No provider/search bean is registered: projection/readback needs zero generation.
        assertEquals(1, beans.getSingletonCount());
    }
}
