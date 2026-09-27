package com.example.lms.util;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelevanceScorerTest {

    @Test
    void oppositeEmbeddingsStayWithinTheDocumentedScoreRange() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query"))
                .thenReturn(Response.from(Embedding.from(new float[] {1.0f, 0.0f})));
        when(model.embed("opposite"))
                .thenReturn(Response.from(Embedding.from(new float[] {-1.0f, 0.0f})));

        RelevanceScorer scorer = new RelevanceScorer(model);

        double cosineOnly = scorer.score("query", "opposite");
        double combined = scorer.score("query", "opposite", 0.0);

        assertAll(
                () -> assertTrue(cosineOnly >= 0.0 && cosineOnly <= 1.0,
                        () -> "cosine score must be in [0,1], but was " + cosineOnly),
                () -> assertTrue(combined >= 0.0 && combined <= 1.0,
                        () -> "combined score must be in [0,1], but was " + combined));
    }

    @Test
    void sameDirectionAndZeroVectorsKeepTheirExistingBounds() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query"))
                .thenReturn(Response.from(Embedding.from(new float[] {1.0f, 0.0f})));
        when(model.embed("same"))
                .thenReturn(Response.from(Embedding.from(new float[] {1.0f, 0.0f})));
        when(model.embed("zero"))
                .thenReturn(Response.from(Embedding.from(new float[] {0.0f, 0.0f})));

        RelevanceScorer scorer = new RelevanceScorer(model);

        assertAll(
                () -> assertEquals(1.0, scorer.score("query", "same"), 1.0e-8),
                () -> assertEquals(0.0, scorer.score("query", "zero")));
    }

    @Test
    void embeddingFailureRemainsFailSoft() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenThrow(new IllegalStateException("synthetic failure"));

        assertEquals(0.0, new RelevanceScorer(model).score("query", "document"));
    }
}
