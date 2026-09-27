package com.example.lms.search;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.api.RagOrchestratorController;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DeadlineProbe} contract: ordered per-request budget events, the
 * cancelled marker, bounded size, and cross-thread visibility into the
 * endpoint worker via {@code ContextPropagation}'s shared trace map.
 */
class DeadlineProbeTest {

    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.context().clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Test
    void recordsOrderedEventsWithRemainingBudget() {
        TimeBudgetContext.set(new TimeBudget(5_000));

        DeadlineProbe.enter("stage.a");
        DeadlineProbe.skip("stage.b", "test_reason");
        DeadlineProbe.finish("stage.a");

        List<String> timeline = DeadlineProbe.timeline();
        assertEquals(3, timeline.size());
        assertTrue(timeline.get(0).startsWith("stage.a|remain="));
        assertTrue(timeline.get(0).endsWith("|enter"));
        assertTrue(timeline.get(1).startsWith("stage.b|remain="));
        assertTrue(timeline.get(1).endsWith("|skip:test_reason"));
        assertTrue(timeline.get(2).endsWith("|finish"));
    }

    @Test
    void noRequestBudgetRecordsMinusOne() {
        DeadlineProbe.enter("stage.nobudget");
        assertEquals("stage.nobudget|remain=-1|enter", DeadlineProbe.timeline().get(0));
    }

    @Test
    void cancelledBudgetMarksEntries() {
        TimeBudget budget = new TimeBudget(60_000);
        TimeBudgetContext.set(budget);
        budget.cancel();

        DeadlineProbe.skip("stage.c", "after_cancel");
        assertTrue(DeadlineProbe.timeline().get(0).contains("remain=0!"),
                "cancelled budget must mark remain with '!', got " + DeadlineProbe.timeline());
    }

    @Test
    void timelineIsBounded() {
        for (int i = 0; i < 100; i++) {
            DeadlineProbe.enter("stage." + i);
        }
        assertEquals(64, DeadlineProbe.timeline().size());
    }

    @Test
    void controllerTimeoutShowsCrossThreadTimeline() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);

        CountDownLatch workerDone = new CountDownLatch(1);
        when(facade.query(any())).thenAnswer(inv -> {
            try {
                Thread.sleep(1_500L);
                return new QueryResponse();
            } finally {
                workerDone.countDown();
            }
        });

        TimeBudgetContext.set(new TimeBudget(300));
        QueryRequest req = new QueryRequest();
        req.query = "q";
        req.topK = 5;
        req.seedOnly = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.query(req));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        workerDone.await(3, TimeUnit.SECONDS);

        List<String> timeline = DeadlineProbe.timeline();
        assertTrue(timeline.stream().anyMatch(e -> e.startsWith("rag.endpoint|") && e.endsWith("|enter")),
                "missing endpoint entry: " + timeline);
        assertTrue(timeline.stream().anyMatch(e -> e.startsWith("rag.endpoint|remain=0!|cancelled")),
                "missing cancelled endpoint event: " + timeline);
        // Written from the worker thread into the caller's shared trace map.
        assertTrue(timeline.stream().anyMatch(e -> e.startsWith("rag.worker|") && e.endsWith("|enter")),
                "missing worker entry: " + timeline);
        assertTrue(timeline.stream().anyMatch(e -> e.startsWith("rag.worker|") && e.endsWith("|finish")),
                "missing worker finish: " + timeline);
    }

    @Test
    void emergencySkipSurfacesInQueryDebugTimeline() throws Exception {
        // End-to-end: an exhausted request budget gates the orchestrator's
        // emergency leg, and the debug map exposes the recorded skip.
        com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator orchestrator =
                new com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator();
        com.example.lms.service.rag.LangChainRAGService ragService =
                mock(com.example.lms.service.rag.LangChainRAGService.class);
        AtomicInteger leafCalls = new AtomicInteger();
        when(ragService.asContentRetriever(anyString()))
                .thenReturn((dev.langchain4j.rag.content.retriever.ContentRetriever) query -> {
                    leafCalls.incrementAndGet();
                    return List.of();
                });
        org.springframework.test.util.ReflectionTestUtils
                .setField(orchestrator, "langChainRAGService", ragService);

        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L);

        QueryRequest req = new QueryRequest();
        req.query = "debug surface check";
        req.topK = 3;
        req.useVector = true;
        QueryResponse response = orchestrator.query(req);

        assertEquals(1, leafCalls.get());
        assertEquals("skipped:request_budget_exhausted",
                response.debug.get("retrieval.emergency"));
        Object timeline = response.debug.get(DeadlineProbe.KEY);
        assertTrue(timeline instanceof List<?> list
                        && list.stream().anyMatch(e -> String.valueOf(e).startsWith("rag.emergency|")),
                "deadline.timeline must expose the skipped emergency stage, got " + timeline);
    }
}
