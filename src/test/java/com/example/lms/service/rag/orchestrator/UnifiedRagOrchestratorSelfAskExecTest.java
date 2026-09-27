package com.example.lms.service.rag.orchestrator;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskPlanner;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the SelfAsk exec wiring in UnifiedRagOrchestrator:
 * the 3-lane planner must run only when the caller flag is set and the
 * request budget is alive, produced sub-queries merge into bounded local
 * legs (pure vector leaf — never web), and every skip/failure path records
 * a stable reason instead of silently degrading.
 */
class UnifiedRagOrchestratorSelfAskExecTest {

    @AfterEach
    void clearTrace() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private static UnifiedRagOrchestrator.QueryRequest baseRequest() {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        return request;
    }

    private static LangChainRAGService vectorService(AtomicInteger leafCalls, List<String> leafQueries) {
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            leafQueries.add(query.text());
            return List.of(Content.from(TextSegment.from("vector evidence for " + query.text())));
        });
        return ragService;
    }

    private static SelfAskPlanner plannerReturning(List<SelfAskPlanner.SubQuestion> lanes) {
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.generateThreeLanes(anyString(), anyLong())).thenReturn(lanes);
        return planner;
    }

    @Test
    void flagOffKeepsPlannerDormant() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger leafCalls = new AtomicInteger();
        List<String> leafQueries = new ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService",
                vectorService(leafCalls, leafQueries));
        SelfAskPlanner planner = plannerReturning(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, "sub query", Map.of())));
        ReflectionTestUtils.setField(orchestrator, "selfAskPlanner", planner);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "plain request without flag";
        request.useVector = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        verify(planner, never()).generateThreeLanes(anyString(), anyLong());
        assertEquals("not_requested", response.debug.get("selfAsk.exec"));
        assertTrue(request.selfAskSubQueries == null || request.selfAskSubQueries.isEmpty());
        assertEquals(1, leafCalls.get(), "only the main vector leg may run");
    }

    @Test
    void flagOnExecutesPlannerAndMergesLocalLegs() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger leafCalls = new AtomicInteger();
        List<String> leafQueries = new ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService",
                vectorService(leafCalls, leafQueries));
        SelfAskPlanner planner = plannerReturning(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, "background fact", Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, "counter evidence", Map.of())));
        ReflectionTestUtils.setField(orchestrator, "selfAskPlanner", planner);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "ambiguous multi-hop question";
        request.useVector = true;
        request.enableSelfAsk = true;
        request.topK = 6;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        verify(planner, times(1)).generateThreeLanes(anyString(), anyLong());
        assertEquals("executed:lanes=2", response.debug.get("selfAsk.exec"));
        assertEquals(List.of("background fact", "counter evidence"), request.selfAskSubQueries);
        // Main vector leg + one bounded SELFASK-VECTOR leg per sub-query.
        assertEquals(3, leafCalls.get());
        assertTrue(leafQueries.contains("background fact"));
        assertTrue(leafQueries.contains("counter evidence"));
        assertEquals(2, ((Number) response.debug.get("stage.selfask.legs")).intValue());
        assertTrue(response.results.stream()
                .anyMatch(doc -> "SELFASK-VECTOR".equals(doc.source)
                        && Boolean.TRUE.equals(doc.meta == null ? null : doc.meta.get("_selfask"))));
    }

    @Test
    void exhaustedRequestBudgetSkipsPlannerAndLegs() throws Exception {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger leafCalls = new AtomicInteger();
        List<String> leafQueries = new ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService",
                vectorService(leafCalls, leafQueries));
        SelfAskPlanner planner = plannerReturning(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, "sub", Map.of())));
        ReflectionTestUtils.setField(orchestrator, "selfAskPlanner", planner);

        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L); // request deadline already spent before query entry

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "budget-dead selfask check";
        request.useVector = true;
        request.enableSelfAsk = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        verify(planner, never()).generateThreeLanes(anyString(), anyLong());
        assertEquals("skipped:request_budget_exhausted", response.debug.get("selfAsk.exec"));
        assertTrue(request.selfAskSubQueries == null || request.selfAskSubQueries.isEmpty());
        // Only the main vector leg ran (its own budget contract still applies).
        assertEquals(1, leafCalls.get());
    }

    @Test
    void plannerFailureFallsSoftBackToRegularPath() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger leafCalls = new AtomicInteger();
        List<String> leafQueries = new ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService",
                vectorService(leafCalls, leafQueries));
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.generateThreeLanes(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("synthetic planner outage"));
        ReflectionTestUtils.setField(orchestrator, "selfAskPlanner", planner);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "planner failure path";
        request.useVector = true;
        request.enableSelfAsk = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("failed:IllegalStateException", response.debug.get("selfAsk.exec"));
        assertTrue(request.selfAskSubQueries == null || request.selfAskSubQueries.isEmpty());
        // Regular pipeline stays alive: main vector leg still produced docs.
        assertEquals(1, leafCalls.get());
        assertTrue(response.results.stream().allMatch(doc -> "VECTOR".equals(doc.source)));
    }

    @Test
    void seedOnlyRequestSkipsPlanning() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        SelfAskPlanner planner = plannerReturning(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, "sub", Map.of())));
        ReflectionTestUtils.setField(orchestrator, "selfAskPlanner", planner);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "seed only replay";
        request.seedOnly = true;
        request.enableSelfAsk = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        verify(planner, never()).generateThreeLanes(anyString(), anyLong());
        assertEquals("skipped:seed_only", response.debug.get("selfAsk.exec"));
        assertEquals(Boolean.TRUE, response.debug.get("seed.only"));
    }

    @Test
    void flagOnWithoutPlannerRecordsMissing() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "planner absent check";
        request.enableSelfAsk = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("planner_missing", response.debug.get("selfAsk.exec"));
        assertEquals("missing_selfAskPlanner", response.debug.get("selfAsk"));
        assertTrue(request.selfAskSubQueries == null || request.selfAskSubQueries.isEmpty());
    }
}
