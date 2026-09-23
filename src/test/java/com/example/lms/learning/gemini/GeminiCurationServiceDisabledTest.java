package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.search.TraceStore;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeminiCurationServiceDisabledTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiCurationService.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertFalse(source.contains(
                "TraceStore.put(\"knowledge.curation.disabledReason\",\n                    disabledReason == null || disabledReason.isBlank() ? null : disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"knowledge.curation.disabledReason\",\n                    disabledReason == null || disabledReason.isBlank() ? null : SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void failSoftLogsDoNotRenderThrowableToString() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiCurationService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(e.toString(), 240)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(t.toString(), 240)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 240)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(t.getMessage(), 240)"));
        assertTrue(source.contains("Gemini curation failed sessionHash={} errorHash={} errorLength={}"));
        assertTrue(source.contains("GeminiCurationService: reinforcement failed (ignored). errorHash={} errorLength={}"));
        assertTrue(source.contains("Applying knowledge delta failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void traceResultFallbackLogsSafeErrorTypeWithoutRecursiveTraceWrite() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiCurationService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("GeminiCurationService: trace result skipped errorType={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
        assertFalse(source.contains("TraceStore.put(\"knowledge.curation.traceFailure\""));
    }

    @Test
    void geminiClientFailSoftDoesNotRenderThrowableToString() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/GeminiClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(e.toString(), 240)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 240)"));
        assertTrue(source.contains("[Gemini] translate API failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("[Gemini] generate API failed. errorHash={} errorLength={}"));
    }

    @Test
    void disabledCurationDoesNotCallGeminiOrApplyDelta() {
        GeminiClient gemini = mock(GeminiClient.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        EmbeddingStoreManager embeddings = mock(EmbeddingStoreManager.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        GeminiCurationService service = service(gemini, knowledgeBase, embeddings, memory);
        ReflectionTestUtils.setField(service, "enabled", false);

        GeminiCurationService.CurationResult result = service.ingestWithResult(event());

        assertFalse(result.applied());
        assertEquals("curation_disabled", result.disabledReason());
        assertEquals(0, result.delta().memories().size());
        assertEquals("curation_disabled", TraceStore.get("knowledge.curation.disabledReason"));
        verifyNoInteractions(gemini, knowledgeBase, embeddings, memory);
    }

    @Test
    void emptyShimDeltaIsNotAppliedAsSuccess() {
        GeminiClient gemini = mock(GeminiClient.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        EmbeddingStoreManager embeddings = mock(EmbeddingStoreManager.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        GeminiCurationService service = service(gemini, knowledgeBase, embeddings, memory);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(gemini.curate(any(LearningEvent.class), anyString(), any(Duration.class)))
                .thenReturn(new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of()));

        GeminiCurationService.CurationResult result = service.ingestWithResult(event());

        assertFalse(result.applied());
        assertEquals("curation_empty_or_not_implemented", result.disabledReason());
        verifyNoInteractions(knowledgeBase, embeddings, memory);
    }

    private static GeminiCurationService service(GeminiClient gemini,
                                                 KnowledgeBaseService knowledgeBase,
                                                 EmbeddingStoreManager embeddings,
                                                 MemoryReinforcementService memory) {
        GeminiCurationService service = new GeminiCurationService(gemini, knowledgeBase, embeddings, memory);
        ReflectionTestUtils.setField(service, "modelId", "gemini-2.5-pro");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 30L);
        ReflectionTestUtils.setField(service, "minConfidence", 0.5d);
        return service;
    }

    private static LearningEvent event() {
        return new LearningEvent("s1", "query", "answer", List.of(), List.of(), 1.0d, 0.0d);
    }
}
