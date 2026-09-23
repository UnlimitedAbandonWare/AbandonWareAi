package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTraceSnapshotPointerPersisterTest {

    @Test
    void capturesTraceHtmlAndAppendsSafeSnapshotPointer() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(eq("chat.trace_html.final"), eq("POST"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenReturn("snap-1");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "chat.trace_html.final",
                "POST",
                "/api/chat",
                Map.of("existing", "value"),
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertEquals(77L, turnId);
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), persisted.capture());
        assertTrue(persisted.getValue().startsWith("?TRACESNAP?snap-1|v1|"));
        assertFalse(persisted.getValue().contains("<html>trace</html>"));
    }

    @Test
    void persistsAgentVisibleHarmonyMetadataEvenWhenTraceHtmlIsBlank() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chat.harmony.postprocess.agentVisible", true);
        meta.put("chat.harmony.postprocess.decision", "smooth_chat");
        meta.put("chat.harmony.postprocess.reason", "answer_flow_balanced");
        meta.put("chat.harmony.postprocess.weightedScore", 0.628d);
        meta.put("debug.ai.metrics.nextAction", "continue_observing_chat_harmony");
        meta.put("debug.ai.metrics.nextReason", "smooth_chat");
        when(store.captureCustom(eq("chat.trace_html.final"), eq("SSE"), eq("/api/chat/stream"), eq(null), eq(null), any(), any()))
                .thenReturn("snap-harmony");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(88L);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "chat.trace_html.final",
                "SSE",
                "/api/chat/stream",
                meta,
                "   ",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertEquals(88L, turnId);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(store).captureCustom(eq("chat.trace_html.final"), eq("SSE"), eq("/api/chat/stream"), eq(null), eq(null), any(), html.capture());
        assertFalse(html.getValue().isBlank());
        assertFalse(html.getValue().contains("ownerToken"));
        assertFalse(html.getValue().contains("Reply exactly OK"));
        org.junit.jupiter.api.Assertions.assertTrue(html.getValue().contains("continue_observing_chat_harmony"));
        org.junit.jupiter.api.Assertions.assertTrue(html.getValue().contains("smooth_chat"));
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), persisted.capture());
        assertTrue(persisted.getValue().startsWith("?TRACESNAP?snap-harmony|v1|"));
        assertFalse(persisted.getValue().contains("continue_observing_chat_harmony"));
    }

    @Test
    void durableProjectionRestoresAfterRingEvictionWithoutPersistingRawTraceContent() {
        TraceSnapshotStore store = enabledStore(1);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(91L);
        String privateSentinel = "ownerToken=PRIVATE_TRACE_56";
        String rawHtml = "<section>" + privateSentinel + "</section>";

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "durable_probe",
                "POST",
                "/api/chat/private-path?ownerToken=PRIVATE_TRACE_56",
                Map.of(
                        "trace.id", "private-trace-56",
                        "ui.traceHtml.kind", "splitPanel",
                        "chat.harmony.postprocess.decision", "smooth_chat"),
                rawHtml,
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), persisted.capture());
        String envelope = persisted.getValue();
        assertEquals(91L, turnId);
        assertTrue(envelope.startsWith("?TRACESNAP?"));
        assertTrue(envelope.length() <= 4_096, envelope);
        assertFalse(envelope.contains(privateSentinel), envelope);
        assertFalse(envelope.contains(rawHtml), envelope);

        String snapshotId = envelope.substring("?TRACESNAP?".length()).split("\\|", 2)[0];
        assertTrue(store.get(snapshotId).isPresent());
        assertNotNull(store.captureCustom(
                "evict_probe",
                "POST",
                "/api/chat/other",
                200,
                null,
                Map.of("trace.id", "private-trace-56-next"),
                null));
        assertTrue(store.get(snapshotId).isEmpty(), "the durable fallback must not depend on the live ring");

        ChatApiController.MessageDto restored = ChatTraceMetaMessageRestorer.restore(
                91L,
                envelope,
                LocalDateTime.of(2026, 8, 26, 12, 0),
                true).orElseThrow();
        assertTrue(restored.content().contains("storageMode=durable_fallback"), restored.content());
        assertTrue(restored.content().contains("reason=durable_probe"), restored.content());
        assertTrue(restored.content().contains("method=POST"), restored.content());
        assertTrue(restored.content().contains("pathHash=hash:"), restored.content());
        assertFalse(restored.content().contains(privateSentinel), restored.content());
        assertFalse(restored.content().contains("private-path"), restored.content());
    }

    @Test
    void skipsInvalidSnapshotIdsWithoutAppendingMessage() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(eq("reason"), eq("GET"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenReturn("../bad");

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "reason",
                "GET",
                "/api/chat",
                null,
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertNull(turnId);
        verify(history, never()).appendMessageReturningId(any(), any(), any());
    }

    @Test
    void snapshotCaptureFailureLeavesTraceBreadcrumbWithoutRawValues() {
        TraceStore.clear();
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        String raw = "ownerToken=secret-trace";
        when(store.captureCustom(eq("reason"), eq("POST"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenThrow(new IllegalStateException(raw));

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                "reason",
                "POST",
                "/api/chat",
                null,
                "<html>trace</html>",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertNull(turnId);
        assertEquals(Boolean.TRUE, TraceStore.get("chat.traceSnapshotPointer.suppressed.persist"));
        assertEquals("IllegalStateException",
                TraceStore.get("chat.traceSnapshotPointer.suppressed.persist.errorType"));
        assertEquals("persist", TraceStore.get("chat.traceSnapshotPointer.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("chat.traceSnapshotPointer.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        verify(history, never()).appendMessageReturningId(any(), any(), any());
        TraceStore.clear();
    }

    private static TraceSnapshotStore enabledStore(int maxSize) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> htmlProvider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(htmlProvider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", maxSize);
        ReflectionTestUtils.setField(store, "maxValueLen", 1_000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "htmlEnabled", false);
        return store;
    }
}
