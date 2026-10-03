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
