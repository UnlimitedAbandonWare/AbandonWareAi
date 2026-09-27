package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.AnswerQualityEvaluator;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvidenceRepairHandlerHonestyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void repairExceptionIsEscalatedWithTraceInsteadOfFakeEmptySuccess() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(Query.class))).thenThrow(new RuntimeException("repair backend down"));
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> handler.retrieve(new Query("repair honesty query")));

        assertTrue(ex.getMessage().contains("repair retrieval failed"));
        assertEquals("failed", TraceStore.get("retrieval.stage.repair.innerStatus"));
        assertEquals("RuntimeException", TraceStore.get("retrieval.stage.repair.exceptionType"));
        assertEquals("silent-failure", TraceStore.get("retrieval.stage.repair.failureClass"));
        Object hash = TraceStore.get("retrieval.stage.repair.queryHash12");
        assertFalse(String.valueOf(hash).contains("repair honesty query"));
        assertEquals(12, String.valueOf(hash).length());
    }

    @Test
    void repairEmptyResultIsRecordedAsEmptyNotFailure() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(Query.class))).thenReturn(List.of());
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");

        List<Content> out = handler.retrieve(new Query("repair empty query"));

        assertTrue(out.isEmpty());
        assertEquals("empty", TraceStore.get("retrieval.stage.repair.innerStatus"));
        assertEquals("", TraceStore.get("retrieval.stage.repair.failureClass"));
    }

    @Test
    void repairCancellationIsClassifiedSeparatelyFromSilentFailure() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(Query.class)))
                .thenThrow(new java.util.concurrent.CancellationException("cancelled ownerToken abc123"));
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");

        assertThrows(IllegalStateException.class, () -> handler.retrieve(new Query("repair cancellation query")));

        assertEquals("failed", TraceStore.get("retrieval.stage.repair.innerStatus"));
        assertEquals("CancellationException", TraceStore.get("retrieval.stage.repair.exceptionType"));
        assertEquals("cancelled", TraceStore.get("retrieval.stage.repair.failureClass"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));
    }

    @Test
    void criticReasonTraceUsesSafeMessage() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(Query.class))).thenReturn(List.of(Content.from("repair evidence")));
        AnswerQualityEvaluator evaluator = mock(AnswerQualityEvaluator.class);
        String supabaseSecret = "sb_secret_" + "criticreason123456";
        when(evaluator.evaluateRetrieval(anyString(), anyList(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(new AnswerQualityEvaluator.RetrievalEvaluation(
                        AnswerQualityEvaluator.Decision.REPAIR_WITH_WEB,
                        0.4,
                        1,
                        1,
                        "weak_relevance Authorization: " + "Bearer " + "fake-token-critic-secret " + supabaseSecret));
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");
        ReflectionTestUtils.setField(handler, "criticEnabled", true);
        ReflectionTestUtils.setField(handler, "evaluator", evaluator);

        handler.handle(new Query("critic repair query"), new java.util.ArrayList<>(List.of(Content.from("draft evidence"))));

        String reason = String.valueOf(TraceStore.get("rag.critic.reason"));
        assertTrue(reason.contains("weak_relevance"));
        assertFalse(reason.contains("Authorization"));
        assertFalse(reason.contains("fake-token-critic-secret"));
        assertFalse(reason.contains(supabaseSecret));
        assertFalse(reason.contains("sb_secret_"));
    }

    @Test
    void abstainDecisionDoesNotTriggerWebRepair() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        AnswerQualityEvaluator evaluator = mock(AnswerQualityEvaluator.class);
        when(evaluator.evaluateRetrieval(anyString(), anyList(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(new AnswerQualityEvaluator.RetrievalEvaluation(
                        AnswerQualityEvaluator.Decision.ABSTAIN,
                        0.25,
                        1,
                        1,
                        "evaluation_unavailable"));
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");
        ReflectionTestUtils.setField(handler, "criticEnabled", true);
        ReflectionTestUtils.setField(handler, "evaluator", evaluator);
        List<Content> accumulator = new java.util.ArrayList<>(List.of(Content.from("draft evidence")));

        handler.handle(new Query("critic repair query"), accumulator);

        verifyNoInteractions(web);
        assertEquals(1, accumulator.size());
        assertEquals(0, TraceStore.get("rag.critic.retry.count"));
    }
}
