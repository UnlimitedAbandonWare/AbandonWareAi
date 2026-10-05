package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.Triple;
import com.example.lms.dto.learning.Rule;
import com.example.lms.dto.learning.Alias;
import com.example.lms.dto.learning.Term;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.search.TraceStore;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiCurationConfidenceOrderTest {
    private final GeminiClient client = mock(GeminiClient.class);
    private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
    private final EmbeddingStoreManager embeddings = mock(EmbeddingStoreManager.class);
    private final MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
    private final GeminiCurationService service = new GeminiCurationService(client, knowledge, embeddings, memory);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void filtersLowConfidenceBeforeBothKnowledgeApplyAndEmbeddingIndex() {
        MemorySnippet low = new MemorySnippet("Synthetic low-confidence memory", "fixture", 0.2);
        MemorySnippet high = new MemorySnippet("Synthetic high-confidence memory", "fixture", 0.9);
        KnowledgeDelta original = delta(List.of(low, high));

        GeminiCurationService.CurationResult result = ingest(original);

        ArgumentCaptor<KnowledgeDelta> applied = ArgumentCaptor.forClass(KnowledgeDelta.class);
        verify(knowledge).apply(applied.capture());
        ArgumentCaptor<List<MemorySnippet>> indexed = ArgumentCaptor.forClass(List.class);
        verify(embeddings).index(indexed.capture());
        assertAll(
                () -> assertEquals(List.of(high), indexed.getValue()),
                () -> assertEquals(List.of(high), applied.getValue().memories()),
                () -> assertEquals(List.of(high), result.delta().memories()));
        assertTrue(result.applied());
        assertEquals(original.triples(), applied.getValue().triples());
        assertEquals(original.rules(), applied.getValue().rules());
        assertEquals(original.aliases(), applied.getValue().aliases());
        assertEquals(original.protectedTerms(), applied.getValue().protectedTerms());
        assertEquals(List.of(low, high), original.memories());
        verify(memory).reinforceWithSnippet(eq("fixture-session"), eq("synthetic query"),
                eq(high.text()), eq("ASSISTANT"), eq(0.9));
        verify(memory, never()).reinforceWithSnippet(anyString(), any(), eq(low.text()), anyString(), anyDouble());
    }

    @Test
    void allLowConfidenceUsesTheExistingEmptyIndexNoOp() {
        GeminiCurationService.CurationResult result = ingest(delta(List.of(
                new MemorySnippet("Synthetic rejected memory", "fixture", 0.2))));

        verify(embeddings).index(List.of());
        ArgumentCaptor<KnowledgeDelta> applied = ArgumentCaptor.forClass(KnowledgeDelta.class);
        verify(knowledge).apply(applied.capture());
        assertEquals(List.of(), applied.getValue().memories());
        assertEquals(List.of(), result.delta().memories());
        verifyNoInteractions(memory);
    }

    @Test
    void excludesBlankNullTextAndNonFiniteConfidenceButKeepsThreshold() {
        MemorySnippet nullText = mock(MemorySnippet.class);
        when(nullText.text()).thenReturn(null);
        when(nullText.confidence()).thenReturn(0.9);
        MemorySnippet boundary = new MemorySnippet("Synthetic threshold memory", "fixture", 0.5);
        ingest(delta(List.of(new MemorySnippet(" ", "fixture", 0.9), nullText,
                new MemorySnippet("Synthetic NaN memory", "fixture", Double.NaN),
                new MemorySnippet("Synthetic infinite memory", "fixture", Double.POSITIVE_INFINITY), boundary)));

        verify(embeddings).index(List.of(boundary));
        verify(memory).reinforceWithSnippet(eq("fixture-session"), eq("synthetic query"),
                eq(boundary.text()), eq("ASSISTANT"), eq(0.5));
        verifyNoMoreInteractions(memory);
    }

    private GeminiCurationService.CurationResult ingest(KnowledgeDelta delta) {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "modelId", "synthetic-model");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 30L);
        ReflectionTestUtils.setField(service, "minConfidence", 0.5d);
        when(client.curate(any(LearningEvent.class), anyString(), any(Duration.class))).thenReturn(delta);
        return service.ingestWithResult(new LearningEvent("fixture-session", "synthetic query",
                "synthetic answer", List.of(), List.of(), 1.0d, 0.0d));
    }

    private static KnowledgeDelta delta(List<MemorySnippet> memories) {
        return new KnowledgeDelta(
                List.of(new Triple("fixture", "relation", "entity", "https://example.invalid/source")),
                List.of(new Rule("synthetic", "lhs", "rhs", 0.9)),
                List.of(new Alias("fixture", "alias")), memories,
                List.of(new Term("fixture-term", "synthetic")));
    }
}
