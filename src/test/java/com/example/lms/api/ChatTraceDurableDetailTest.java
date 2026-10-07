package com.example.lms.api;

import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTraceDurableDetailTest {
    @Test void snapshotWithoutSavedAttributionNeverRecalculatesMissingContext() {
        var analyzer = mock(com.example.lms.trace.attribution.TraceAblationAttributionService.class);
        String html = new TraceHtmlBuilder(analyzer).buildSnapshotHtml("legacy-snapshot", null, null, null,
                null, "chat.trace_html.final", "SSE", "none", null, null,
                Map.of("web.failsoft.starvationFallback.used", true), Map.of());
        verifyNoInteractions(analyzer);
        assertTrue(html.contains("저장된 TAA 요약 없음"));
        assertFalse(html.contains("risk="));
    }

    @Test void denseDiagnosticsRetainAttributionAndObservedExecutionWithinExistingCap() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        for (String field : java.util.List.of("DETAIL_FLAGS", "DETAIL_COUNTS", "DETAIL_NUMBERS", "DETAIL_LABELS")) {
            var declared = ChatTraceMetaMessageRestorer.class.getDeclaredField(field);
            declared.setAccessible(true);
            for (String key : (java.util.Set<String>) declared.get(null)) {
                source.put(key, switch (field) {
                    case "DETAIL_FLAGS" -> false;
                    case "DETAIL_COUNTS" -> 0;
                    case "DETAIL_NUMBERS" -> 0.5d;
                    default -> "safe";
                });
            }
        }
        source.put("taa.version", "taa-1.0");
        source.put("taa.outcome", "STARVATION");
        source.put("routing.executionPlan.primaryMode", "EXTREMEZ");
        source.put("observedModel", "synthetic/model");
        var projected = ChatTraceMetaMessageRestorer.projectDiagnostics(source);
        assertTrue(projected.size() <= 80);
        for (String key : java.util.List.of("taa.version", "taa.outcome", "taa.outcome.risk",
                "taa.topContributor.id", "taa.topContributor.group", "taa.candidate.count", "taa.beam.count",
                "routing.executionPlan.primaryMode", "extremez.execute.activated", "observedModel",
                "finalAnswer.releaseAllowed", "finalAnswer.releaseReason")) {
            assertTrue(projected.containsKey("diag." + key), key);
        }
        StringBuilder encoded = new StringBuilder(CORE);
        projected.forEach((key, value) -> encoded.append(key).append('=').append(value).append('\n'));
        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope(encoded.toString(), "v3"), 77L).orElseThrow();
        assertEquals("taa-1.0", pointer.diagnostics().get("taa.version"));
    }

    @Test void oneRenderedResultTravelsToSnapshotAndDurableSummaryWithoutThreadLocalMerge() {
        var analyzer = spy(new com.example.lms.trace.attribution.TraceAblationAttributionService());
        var builder = new TraceHtmlBuilder(analyzer);
        Map<String, Object> captured = Map.of("web.failsoft.starvationFallback.used", true);
        com.example.lms.search.TraceStore.clear();
        com.example.lms.search.TraceStore.put("unrelated.request.field", "must-not-merge");
        try {
            var rendered = builder.buildSplitPanelWithMetadata(null, java.util.List.of(), java.util.List.of(),
                    null, captured, true);
            assertTrue(rendered.html().contains("Trace-Ablation Attribution"));
            assertEquals("taa-1.0", rendered.metadata().get("taa.version"));
            assertFalse(captured.containsKey("taa.version"));
            assertFalse(rendered.metadata().containsKey("unrelated.request.field"));
            assertThrows(UnsupportedOperationException.class, () -> rendered.metadata().put("bad", true));
            TraceSnapshotStore store = mock(TraceSnapshotStore.class);
            ChatHistoryService history = mock(ChatHistoryService.class);
            when(store.captureCustom(any(), any(), any(), any(), any(), any(), any())).thenReturn("owned-snapshot");
            when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);
            ChatTraceSnapshotPointerPersister.persist(7L, 66L, "chat.trace_html.final", "SSE", "/api/chat",
                    rendered.metadata(), rendered.html(), store, history, LoggerFactory.getLogger(getClass()));
            ArgumentCaptor<Map<String, Object>> snap = ArgumentCaptor.forClass(Map.class);
            verify(store).captureCustom(any(), any(), any(), any(), any(), snap.capture(), eq(rendered.html()));
            assertEquals(rendered.metadata().get("taa.outcome.risk"), snap.getValue().get("taa.outcome.risk"));
            ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
            verify(history).appendMessageReturningId(eq(7L), eq("system"), saved.capture());
            var restored = ChatTraceMetaMessageRestorer.parseSnapshotPointer(saved.getValue(), 77L).orElseThrow();
            assertEquals(rendered.metadata().get("taa.outcome.risk"), restored.diagnostics().get("taa.outcome.risk"));
            String restoredHtml = builder.buildSnapshotHtml("owned-snapshot", null, null, null, null,
                    "chat.trace_html.final", "SSE", "none", null, null, restored.diagnostics(), Map.of());
            assertTrue(restoredHtml.contains("risk=" + String.format(java.util.Locale.ROOT, "%.3f",
                    rendered.metadata().get("taa.outcome.risk"))));
            verify(analyzer, times(1)).analyze(anyMap(), anyList(), isNull());
        } finally { com.example.lms.search.TraceStore.clear(); }
    }

    @Test void extremeZProducerFieldsSurviveTypedDurableRoundtrip() {
        Map<String, Object> observed = Map.of(
                "routing.executionPlan.primaryMode", "EXTREMEZ", "orch.mode", "NORMAL",
                "extremez.execute.activated", true, "extremez.execute.refinedCount", 0,
                "extremez.activated", false, "extremez.skipReason", "burst_handler_already_ran");
        var projection = ChatTraceMetaMessageRestorer.projectDiagnostics(observed);
        StringBuilder encoded = new StringBuilder(CORE);
        projection.forEach((key, value) -> encoded.append(key).append('=').append(value).append('\n'));
        var restored = ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope(encoded.toString(), "v3"), 77L).orElseThrow();
        assertEquals("EXTREMEZ", restored.diagnostics().get("routing.executionPlan.primaryMode"));
        assertEquals(Boolean.TRUE, restored.diagnostics().get("extremez.execute.activated"));
        String html = new TraceHtmlBuilder(null).buildSnapshotHtml("owned-snapshot", null, null, null, null,
                "chat.trace_html.final", "SSE", "none", null, null, restored.diagnostics(), Map.of());
        assertTrue(html.contains("execute=true"));
        assertTrue(html.contains("handler output=0"));
        assertTrue(html.contains("AOP document growth=false"));
    }

    @Test void storedAttributionSummarySurvivesRingMissWithoutRecalculatingRisk() {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("taa.version", "taa-1.0");
        observed.put("taa.outcome", "STARVATION");
        observed.put("taa.outcome.risk", 0.798d);
        observed.put("taa.topContributor.id", "starvation");
        observed.put("taa.topContributor.group", "retrieval");
        observed.put("taa.candidate.count", 1);
        observed.put("taa.beam.count", 1);
        observed.put("ablation.finalized", true);
        observed.put("ablation.score.final", 0.75d);
        observed.put("taa.rawEvidence", "synthetic private evidence");
        Map<String, String> projected = ChatTraceMetaMessageRestorer.projectDiagnostics(observed);
        assertEquals("s:taa-1.0", projected.get("diag.taa.version"));
        StringBuilder encoded = new StringBuilder(CORE);
        projected.forEach((key, value) -> encoded.append(key).append('=').append(value).append('\n'));
        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope(encoded.toString(), "v3"), 77L).orElseThrow();
        assertEquals(0.798d, pointer.diagnostics().get("taa.outcome.risk"));
        assertEquals(Boolean.TRUE, pointer.diagnostics().get("ablation.finalized"));
        var analyzer = mock(com.example.lms.trace.attribution.TraceAblationAttributionService.class);
        String html = new TraceHtmlBuilder(analyzer).buildSnapshotHtml("owned-snapshot", null, null, null,
                null, "chat.trace_html.final", "SSE", "none", null, null, pointer.diagnostics(), Map.of());
        assertTrue(html.contains("Trace-Ablation Attribution"));
        assertTrue(html.contains("risk=0.798"));
        assertTrue(html.contains("taa-1.0"));
        assertTrue(html.contains("요약만 복원"));
        assertTrue(html.contains("휴리스틱"));
        assertFalse(html.contains("synthetic private evidence"));
        verifyNoInteractions(analyzer);
    }

    private static final String CORE = "storageMode=durable_fallback\nassistantMessageId=66\n"
            + "reason=chat.trace_html.final\nmethod=SSE\npathHash=hash:111111111111\n";
    private static String envelope(String text, String version) {
        return "?TRACESNAP?owned-snapshot|" + version + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void detailedProjectionPreservesTypesAndRegeneratesWithoutLiveRingOrRawHtml() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(any(), any(), any(), any(), any(), any(), any())).thenReturn("owned-snapshot");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("queryTransformer.bypassed", "true");
        source.put("queryTransformer.reason", "retrieval_off_direct");
        source.put("prompt.webCount", 0);
        source.put("prompt.context.refiner.phi", 0.25d);
        source.put("keywordSelection.maxMust", 3);
        source.put("embed.actualDim", 768);
        source.put("vector.fp.dropped", 2);
        source.put("orch.auxDegraded", false);
        source.put("rawQuery", "synthetic private query");
        source.put("prompt.raw", "synthetic private prompt");
        assertEquals(77L, ChatTraceSnapshotPointerPersister.persist(7L, 66L,
                "chat.trace_html.final", "SSE", "/private/path", source,
                "<section>synthetic private html</section>", store, history,
                LoggerFactory.getLogger(ChatTraceDurableDetailTest.class)));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), saved.capture());
        assertTrue(saved.getValue().contains("|v3|"));
        String decoded = new String(Base64.getUrlDecoder().decode(saved.getValue().split("\\|")[2]), StandardCharsets.UTF_8);
        assertTrue(decoded.getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertFalse(decoded.contains("synthetic private"));
        assertFalse(decoded.contains("/private/path"));
        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(saved.getValue(), 77L).orElseThrow();
        assertEquals(66L, pointer.assistantMessageId());
        assertFalse(pointer.legacyFallbackAllowed());
        assertEquals(Boolean.TRUE, pointer.diagnostics().get("queryTransformer.bypassed"));
        assertEquals(0L, pointer.diagnostics().get("prompt.webCount"));
        assertEquals(0.25d, pointer.diagnostics().get("prompt.context.refiner.phi"));
        assertTrue(pointer.projection().size() <= 16);
        assertTrue(pointer.projection().keySet().stream().noneMatch(key -> key.startsWith("diag.")));
        String html = new TraceHtmlBuilder(null).buildSnapshotHtml(pointer.snapshotId(), null, null, null,
                null, pointer.projection().get("reason"), "SSE", pointer.projection().get("pathHash"),
                null, null, pointer.diagnostics(), Map.of());
        assertTrue(html.contains("Query Transformation"));
        assertTrue(html.contains("Keyword Selection"));
        assertTrue(html.contains("Embedding"));
        assertTrue(html.contains("Vector"));
        assertTrue(html.contains("retrieval_off_direct"));
        assertFalse(html.contains("synthetic private"));
        verify(store).captureCustom(any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(store);
    }

    @Test void malformedTypesUnknownFieldsAndMissingAssistantInvalidateTheWholeProjection() {
        for (String bad : new String[] {
                "diag.prompt.webCount=n:-1\n", "diag.prompt.webCount=n:1000001\n",
                "diag.prompt.webCount=b:true\n", "diag.prompt.raw=s:private\n",
                "diag.prompt.context.refiner.phi=f:NaN\n", "diag.prompt.context.refiner.phi=f:Infinity\n",
                "diag.queryTransformer.bypassed=b:TRUE\n", "diag.prompt.webCount=n:1\ndiag.prompt.webCount=n:2\n" }) {
            var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope(CORE + bad, "v3"), 77L).orElseThrow();
            assertTrue(pointer.projection().isEmpty());
            assertTrue(pointer.diagnostics().isEmpty());
            assertNull(pointer.assistantMessageId());
            assertFalse(pointer.legacyFallbackAllowed());
        }
        var missing = ChatTraceMetaMessageRestorer.parseSnapshotPointer(
                envelope(CORE.replace("assistantMessageId=66\n", "") + "diag.prompt.webCount=n:1\n", "v3"), 77L).orElseThrow();
        assertNull(missing.assistantMessageId());
        assertTrue(missing.diagnostics().isEmpty());
    }

    @Test void v3SizeAndLegacyVersionBoundariesRemainIndependent() {
        var valid = ChatTraceMetaMessageRestorer.parseSnapshotPointer(
                envelope(CORE + "diag.prompt.webCount=n:1\n", "v3"), 77L).orElseThrow();
        assertEquals(1L, valid.diagnostics().get("prompt.webCount"));
        var legacy = ChatTraceMetaMessageRestorer.parseSnapshotPointer(
                envelope(CORE + "diag.prompt.webCount=n:1\n", "v2"), 77L).orElseThrow();
        assertTrue(legacy.projection().isEmpty());
        var oversized = ChatTraceMetaMessageRestorer.parseSnapshotPointer(
                envelope(CORE + "\n".repeat(8193), "v3"), 77L).orElseThrow();
        assertTrue(oversized.projection().isEmpty());
        assertTrue(oversized.diagnostics().isEmpty());
    }

    @Test void untrustedContainerAndSecretValuesNeverBecomeDetailedFields() {
        Map<String, Object> source = Map.of(
                "prompt.webCount", Map.of("value", 12),
                "orch.mode", com.example.lms.test.SecretFixtures.openAiKey(),
                "queryTransformer.reason", "synthetic private query",
                "embed.actualDim", -1,
                "prompt.context.refiner.phi", Double.POSITIVE_INFINITY);
        Map<String, String> safe = ChatTraceMetaMessageRestorer.projectDiagnostics(source);
        assertFalse(safe.containsKey("diag.prompt.webCount"));
        assertFalse(safe.containsKey("diag.embed.actualDim"));
        assertFalse(safe.containsKey("diag.prompt.context.refiner.phi"));
        assertTrue(safe.values().stream().allMatch(value -> value.matches("s:hash:[0-9a-f]{12}")));
        assertFalse(safe.toString().contains("synthetic private"));
        assertFalse(safe.toString().contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }
}
