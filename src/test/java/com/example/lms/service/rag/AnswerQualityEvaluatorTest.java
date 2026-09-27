package com.example.lms.service.rag;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerQualityEvaluatorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
    }

    @Test
    void evaluateRetrievalRequestsRepairWhenDocsAreEmpty() {
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(null);

        AnswerQualityEvaluator.RetrievalEvaluation ev =
                evaluator.evaluateRetrieval("what is corrective rag", List.of(), 2, 0.5, 2);

        assertEquals(AnswerQualityEvaluator.Decision.REPAIR_WITH_WEB, ev.decision());
        assertEquals("empty_docs", ev.reason());
        assertEquals(0, ev.docCount());
    }

    @Test
    void evaluateRetrievalRequestsRewriteForTooShortQuery() {
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(null);

        AnswerQualityEvaluator.RetrievalEvaluation ev =
                evaluator.evaluateRetrieval("?", List.of(), 2, 0.5, 2);

        assertEquals(AnswerQualityEvaluator.Decision.REWRITE_QUERY, ev.decision());
        assertEquals("query_too_short", ev.reason());
    }

    @Test
    void embeddingFailureLeavesTraceBreadcrumbWithoutRawMessage() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString()))
                .thenThrow(new IllegalStateException("raw embedding secret"));
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(embeddingModel);

        TraceStore.clear();
        boolean sufficient = evaluator.isSufficient(
                "What is corrective RAG?",
                List.of(Content.from("small evidence chunk")),
                1,
                0.5);

        assertFalse(sufficient);
        assertEquals(true, TraceStore.get("rag.answerQuality.suppressed.isSufficient"));
        assertEquals("IllegalStateException", TraceStore.get("rag.answerQuality.isSufficient.errorType"));
        assertEquals("isSufficient", TraceStore.get("rag.answerQuality.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("rag.answerQuality.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("rag.answerQuality.suppressed.isSufficient.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw embedding secret"));
    }

    @Test
    void evaluateRetrievalAbstainsWhenSimilarityEvaluationIsUnavailable() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString()))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(embeddingModel);
        List<Content> docs = List.of(
                Content.from(TextSegment.from("first evidence", Metadata.from(Map.of("url", "https://one.example")))),
                Content.from(TextSegment.from("second evidence", Metadata.from(Map.of("url", "https://two.example"))))
        );

        AnswerQualityEvaluator.RetrievalEvaluation ev =
                evaluator.evaluateRetrieval("what is corrective rag", docs, 2, 0.5, 2);

        assertEquals(AnswerQualityEvaluator.Decision.ABSTAIN, ev.decision());
        assertEquals("evaluation_unavailable", ev.reason());
    }

    @Test
    void evaluateRetrievalPublishesFaithfulnessProxyTraceWithoutRawQuery() {
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(null);

        AnswerQualityEvaluator.RetrievalEvaluation ev =
                evaluator.evaluateRetrieval("private ownerToken=secret", List.of(), 2, 0.5, 2);

        assertEquals(AnswerQualityEvaluator.Decision.REPAIR_WITH_WEB, ev.decision());
        assertEquals("REPAIR_WITH_WEB", TraceStore.get("rag.answerQuality.decision"));
        assertEquals("empty_docs", TraceStore.get("rag.answerQuality.reason"));
        assertEquals(0.06d, (Double) TraceStore.get("rag.answerQuality.faithfulnessScore"), 1e-9);
        assertEquals(0, TraceStore.get("rag.answerQuality.docCount"));
        assertEquals(0, TraceStore.get("rag.answerQuality.distinctSources"));
        assertFalse(TraceStore.getAll().toString().contains("ownerToken"));
        assertFalse(TraceStore.getAll().toString().contains("private"));
    }

    @Test
    void evaluateRetrievalDownweightsRewriteDecisionFaithfulnessProxy() {
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(null);

        AnswerQualityEvaluator.RetrievalEvaluation ev =
                evaluator.evaluateRetrieval("?", List.of(), 2, 0.5, 2);

        assertEquals(AnswerQualityEvaluator.Decision.REWRITE_QUERY, ev.decision());
        assertEquals(0.08d, (Double) TraceStore.get("rag.answerQuality.faithfulnessScore"), 1e-9);
    }
}
