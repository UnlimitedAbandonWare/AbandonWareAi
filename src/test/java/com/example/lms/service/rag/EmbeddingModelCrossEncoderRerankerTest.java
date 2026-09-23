package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingModelCrossEncoderRerankerTest {

    @Test
    void embeddingFailureReturnsOriginalOrderAndLeavesRedactedBreadcrumb() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenThrow(new IllegalStateException("raw cross encoder secret"));
        EmbeddingModelCrossEncoderReranker reranker = new EmbeddingModelCrossEncoderReranker(
                embeddingModel,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        List<Content> candidates = List.of(Content.from("first"), Content.from("second"));
        TraceStore.clear();
        List<Content> reranked = reranker.rerank("query", candidates, 1);

        assertEquals(List.of(candidates.get(0)), reranked);
        assertEquals(true, TraceStore.get("rag.embeddingCrossEncoderReranker.suppressed.rerank"));
        assertEquals("IllegalStateException",
                TraceStore.get("rag.embeddingCrossEncoderReranker.rerank.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw cross encoder secret"));
    }
}
