package com.example.lms.risk.rdi;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskFeatureExtractorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void textSegmentNumericFailureUsesStableErrorTypeAndKeepsFallback() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{0.25f, 0.75f})));
        Content broken = mock(Content.class);
        when(broken.textSegment()).thenThrow(new NumberFormatException("raw private token"));
        when(broken.toString()).thenReturn("fallback signal");

        RiskFeatureExtractor extractor = new RiskFeatureExtractor(embeddingModel);

        double[] features = extractor.featuresOf(new ListingContext(List.of(broken)));

        assertEquals(4, features.length);
        assertEquals(0.25d, features[0], 0.0001d);
        assertEquals(0.75d, features[1], 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("risk.feature.suppressed.textSegment"));
        assertEquals("invalid_number", TraceStore.get("risk.feature.suppressed.textSegment.errorType"));
        assertEquals("textSegment", TraceStore.get("risk.feature.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("risk.feature.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private token"));
    }
}
