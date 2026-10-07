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
    void persistsOnlyBoundedPublicEvidenceInsideTheExistingAssistantPointer() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(any(), any(), any(), eq(null), eq(null), any(), eq(null), eq(false)))
                .thenReturn("evidence-owned");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);
        var evidence = java.util.List.of(new com.example.lms.dto.RagEvidenceMetadata("W1", "WEB",
                "Synthetic unofficial claim", "https://example.test/B?view=2", null, 4, 6, 1, null, null));
        Long id = ChatTraceSnapshotPointerPersister.persist(7L, 66L, "final", "SSE", "/api/chat/stream",
                Map.of("rawSnippet", "SYNTHETIC_PRIVATE_BODY", "prompt.webCount", 1), null,
                store, history, LoggerFactory.getLogger(getClass()), false, evidence);
        assertEquals(77L, id);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), saved.capture());
        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(saved.getValue(), 77L).orElseThrow();
        assertEquals(66L, pointer.assistantMessageId());
        assertEquals(evidence, pointer.evidence());
        assertEquals(1L, pointer.diagnostics().get("prompt.webCount"));
        assertFalse(pointer.projection().containsKey("publicEvidence"), "source packet is not a diagnostic label");
        assertFalse(saved.getValue().contains("SYNTHETIC_PRIVATE_BODY"));
    }

    @Test
    void oversizedPublicEvidenceDoesNotReplaceOrCorruptTheExistingPointer() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(any(), any(), any(), eq(null), eq(null), any(), eq(null), eq(false)))
                .thenReturn("evidence-bounded");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);
        var evidence = java.util.stream.IntStream.rangeClosed(1, 16).mapToObj(index ->
                new com.example.lms.dto.RagEvidenceMetadata("W" + index, "WEB", "Long title ".repeat(50),
                        "https://example.test/" + index, null, null, null, index, null, null)).toList();
        ChatTraceSnapshotPointerPersister.persist(7L, 66L, "final", "SSE", "/api/chat/stream",
                Map.of("prompt.webCount", 16), null, store, history, LoggerFactory.getLogger(getClass()), false, evidence);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), saved.capture());
        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(saved.getValue(), 77L).orElseThrow();
        assertEquals(66L, pointer.assistantMessageId()); assertTrue(pointer.evidence().isEmpty());
        assertEquals(16L, pointer.diagnostics().get("prompt.webCount"));
    }

    @Test
    void capturesTraceHtmlAndAppendsSafeSnapshotPointer() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(store.captureCustom(eq("chat.trace_html.final"), eq("POST"), eq("/api/chat"), eq(null), eq(null), any(), eq("<html>trace</html>")))
                .thenReturn("snap-1");
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(77L);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                66L,
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
        assertTrue(persisted.getValue().startsWith("?TRACESNAP?snap-1|v2|"));
        assertEquals(66L, ChatTraceMetaMessageRestorer.parseSnapshotPointer(
                persisted.getValue(), 77L).orElseThrow().assistantMessageId());
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
                66L,
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
        assertTrue(persisted.getValue().startsWith("?TRACESNAP?snap-harmony|v2|"));
        assertFalse(persisted.getValue().contains("continue_observing_chat_harmony"));
    }

    @Test
    void metadataOnlyTraceMemoryUsesRealStoreAndAssistantBoundPointer() {
        TraceSnapshotStore store = enabledStore(8);
        ReflectionTestUtils.setField(store, "htmlEnabled", true);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(95L);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L,
                66L,
                "chat.trace_html.final",
                "SSE",
                "/api/chat/stream",
                Map.of("traceMemory.checkpoint.stage", "load",
                        "traceMemory.checkpoint.phase", "memory.loader.assembled"),
                " ",
                store,
                history,
                LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertEquals(95L, turnId);
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), persisted.capture());
        String envelope = persisted.getValue();
        ChatTraceMetaMessageRestorer.SnapshotPointer pointer =
                ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope, 95L).orElseThrow();
        assertEquals(66L, pointer.assistantMessageId());
        String snapshotHtml = store.get(pointer.snapshotId()).orElseThrow().html();
        assertTrue(snapshotHtml.contains("<section data-trace=\"trace-memory\" data-kind=\"metadata-only\">"));
        assertTrue(snapshotHtml.contains("Checkpoint stage"));
        assertFalse(envelope.contains("<section"));

        ChatApiController.MessageDto restored = ChatTraceMetaMessageRestorer.restore(
                95L, envelope, LocalDateTime.of(2026, 9, 27, 2, 0), true).orElseThrow();
        assertTrue(restored.content().contains("data-trace-snapshot-id=\"" + pointer.snapshotId() + "\""));
        assertTrue(restored.content().contains("traceMemoryStage=load"));
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
                66L,
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
                66L,
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
    void doesNotWriteAnUnlinkedNewPointerWhenAssistantSaveDidNotReturnAnId() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        ChatHistoryService history = mock(ChatHistoryService.class);

        Long turnId = ChatTraceSnapshotPointerPersister.persist(
                7L, null, "reason", "POST", "/api/chat", Map.of(), "<html>trace</html>",
                store, history, LoggerFactory.getLogger(ChatTraceSnapshotPointerPersisterTest.class));

        assertNull(turnId);
        verify(store, never()).captureCustom(any(), any(), any(), any(), any(), any(), any());
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
                66L,
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

    @Test
    void executionReceiptSurvivesHistoryRestoreWithoutExposingDebugOrAnotherAnswer() {
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.appendMessageReturningId(eq(7L), eq("system"), any())).thenReturn(91L);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("executionMode.requested", "SELF_ASK");
        metadata.put("executionMode.effective", "AUTO");
        metadata.put("executionMode.reason", "safety-gate");
        metadata.put("executionMode.queryCount", 1);
        metadata.put("executionMode.httpAttempts", 2);
        metadata.put("executionMode.expanded", false);
        metadata.put("prompt", "private synthetic prompt");
        ChatTraceSnapshotPointerPersister.persist(7L, 66L, "receipt", "SSE", "/api/chat/stream",
                metadata, "<p>private debug</p>", enabledStore(1), history,
                LoggerFactory.getLogger(getClass()));
        var captured = ArgumentCaptor.forClass(String.class);
        verify(history).appendMessageReturningId(eq(7L), eq("system"), captured.capture());
        var now = LocalDateTime.of(2026, 10, 5, 12, 0);
        var session = com.example.lms.domain.ChatSession.builder().id(7L).messages(java.util.List.of(
                com.example.lms.domain.ChatMessage.builder().id(66L).role("assistant").content("first").createdAt(now).build(),
                com.example.lms.domain.ChatMessage.builder().id(91L).role("system").content(captured.getValue()).createdAt(now.plusSeconds(1)).build(),
                com.example.lms.domain.ChatMessage.builder().id(67L).role("assistant").content("second").createdAt(now.plusSeconds(2)).build())).build();
        var detail = ChatSessionDetailResponseBuilder.build(session, "fixture", new com.fasterxml.jackson.databind.ObjectMapper(),
                Map.of(), false, LoggerFactory.getLogger(getClass())).getBody();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(detail);
        var receipt = json.path("messages").get(0).path("executionMode");
        assertEquals("SELF_ASK", receipt.path("requested").asText());
        assertEquals("AUTO", receipt.path("effective").asText());
        assertEquals("safety-gate", receipt.path("reason").asText());
        assertEquals(1, receipt.path("queryCount").asInt());
        assertEquals(2, receipt.path("httpAttempts").asInt());
        assertFalse(receipt.path("expanded").asBoolean());
        assertTrue(json.path("messages").get(1).path("executionMode").isNull()
                || json.path("messages").get(1).path("executionMode").isMissingNode());
        assertEquals(0, detail.turnTraces().size());
        assertFalse(json.toString().contains("private"));
    }

    @Test
    void durableReceiptProjectionRejectsUnknownModesAndUnboundedCounts() {
        var invalid = ChatTraceMetaMessageRestorer.projectDiagnostics(Map.of(
                "executionMode.requested", "BYPASS", "executionMode.effective", "SELF_ASK"));
        assertTrue(invalid.isEmpty());
        var projected = ChatTraceMetaMessageRestorer.projectDiagnostics(Map.of(
                "executionMode.requested", "AUTO", "executionMode.effective", "PRIVATE_VALUE",
                "executionMode.reason", "private text", "executionMode.queryCount", 99,
                "executionMode.httpAttempts", -1, "executionMode.expanded", "private flag"));
        assertEquals(Map.of("diag.executionMode.requested", "s:AUTO"), projected);
    }

    @Test
    void ambiguousAnswerPointersCannotChooseAnActualModeFromTheSessionSetting() {
        String fields = "storageMode=durable_fallback\nreason=fixture\nmethod=POST\npathHash=none\n"
                + "assistantMessageId=66\ndiag.executionMode.requested=s:SELF_ASK\n"
                + "diag.executionMode.effective=s:AUTO\ndiag.executionMode.reason=s:safety-gate\n";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fields.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var now = LocalDateTime.of(2026, 10, 5, 12, 0);
        var session = com.example.lms.domain.ChatSession.builder().id(7L).sessionMeta("{\"executionMode\":\"STRIKE\"}")
                .messages(java.util.List.of(
                        com.example.lms.domain.ChatMessage.builder().id(66L).role("assistant").content("answer").createdAt(now).build(),
                        com.example.lms.domain.ChatMessage.builder().id(91L).role("system").content("?TRACESNAP?receipt-a|v3|"+encoded).createdAt(now.plusSeconds(1)).build(),
                        com.example.lms.domain.ChatMessage.builder().id(92L).role("system").content("?TRACESNAP?receipt-b|v3|"+encoded).createdAt(now.plusSeconds(2)).build())).build();
        var detail = ChatSessionDetailResponseBuilder.build(session, "fixture", new com.fasterxml.jackson.databind.ObjectMapper(),
                Map.of(), false, LoggerFactory.getLogger(getClass())).getBody();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(detail);
        for (int index : new int[]{1, 2}) {
            var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(session.getMessages().get(index).getContent(), 91L).orElseThrow();
            assertEquals(66L, pointer.assistantMessageId());
            assertEquals("SELF_ASK", pointer.diagnostics().get("executionMode.requested"));
        }
        assertTrue(json.path("messages").get(0).path("executionMode").isNull()
                || json.path("messages").get(0).path("executionMode").isMissingNode());
        assertEquals(0, detail.turnTraces().size());
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
