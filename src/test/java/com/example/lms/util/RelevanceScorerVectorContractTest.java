package com.example.lms.util;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelevanceScorerVectorContractTest {
    @Test
    void unequalDimensionsCannotBecomeAPerfectPrefixMatch() {
        assertEquals(0.0, scorer(new float[] {1, 0}, new float[] {1, 0, 10}).score("query", "document"));
        assertEquals(0.0, scorer(new float[] {1, 0, 10}, new float[] {1, 0}).score("query", "document"));
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.MAX_VALUE, 1e20f, 1.0f, 1e-30f, Float.MIN_VALUE})
    void cosineRemainsScaleInvariantForFiniteFloatVectors(float scale) {
        double result = scorer(new float[] {scale, scale}, new float[] {scale, scale}).score("query", "document");
        assertTrue(Double.isFinite(result) && result >= 0.0 && result <= 1.0);
        assertEquals(1.0, result, 1e-12);
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void nonFiniteModelCoordinatesFailSoft(float invalid) {
        assertEquals(0.0, scorer(new float[] {invalid, 1}, new float[] {1, 1}).score("query", "document"));
        assertEquals(0.0, scorer(new float[] {1, 1}, new float[] {1, invalid}).score("query", "document"));
    }

    @Test
    void angularDifferenceIsPreservedBeyondCollinearInputs() {
        assertEquals(0.6, scorer(new float[] {3, 4}, new float[] {1, 0}).score("query", "document"), 1e-12);
        assertEquals(0.0, scorer(new float[] {1, 0}, new float[] {0, 1}).score("query", "document"));
        assertEquals(0.0, scorer(new float[] {1, 0}, new float[] {-1, 0}).score("query", "document"));
    }

    @Test
    void zeroAndEmptyVectorsFailSoft() {
        assertEquals(0.0, scorer(new float[] {0, 0}, new float[] {1, 0}).score("query", "document"));
        assertEquals(0.0, scorer(new float[0], new float[0]).score("query", "document"));
    }

    private RelevanceScorer scorer(float[] query, float[] document) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenReturn(Response.from(Embedding.from(query)));
        when(model.embed("document")).thenReturn(Response.from(Embedding.from(document)));
        return new RelevanceScorer(model);
    }
}
