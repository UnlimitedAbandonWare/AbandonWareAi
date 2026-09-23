package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deadline/cancellation propagation for {@link RagOrchestratorController}:
 * the request-scoped {@link TimeBudget} is shared by reference into the
 * endpoint worker (via ContextPropagation), so cancelling it is the
 * cooperative stop signal when {@code future.cancel(false)} cannot interrupt
 * a running task. No external calls — the facade is a stub.
 */
class RagOrchestratorControllerDeadlineTest {

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

    private static QueryRequest request() {
        QueryRequest req = new QueryRequest();
        req.query = "q";
        req.topK = 5;
        req.seedOnly = true;
        return req;
    }

    @Test
    void expiredBudgetRejectsBeforeSubmittingWork() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);

        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> controller.query(request()));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        verify(facade, never()).query(any());
        assertEquals("not_started", TraceStore.context().get("rag.endpoint.workerTermination"));
        assertEquals(false, TraceStore.context().get("rag.endpoint.cancelRequested"));
    }

    @Test
    void timeoutCancelsBudgetAndRunningWorkerStops() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);

        CountDownLatch workerDone = new CountDownLatch(1);
        AtomicBoolean observedCancelled = new AtomicBoolean(false);
        when(facade.query(any())).thenAnswer(inv -> {
            try {
                // Simulates a cooperative downstream stage that would run ~3s
                // unbounded: it stops only when the shared budget is cancelled.
                long deadline = System.currentTimeMillis() + 3_000L;
                while (System.currentTimeMillis() < deadline) {
                    TimeBudget budget = TimeBudgetContext.get();
                    if (budget != null && budget.cancelled()) {
                        observedCancelled.set(true);
                        return new QueryResponse();
                    }
                    Thread.sleep(10L);
                }
                return new QueryResponse();
            } finally {
                workerDone.countDown();
            }
        });

        TimeBudget budget = new TimeBudget(400);
        TimeBudgetContext.set(budget);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> controller.query(request()));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        assertTrue(budget.cancelled(), "controller must mark the shared budget cancelled");
        assertTrue(workerDone.await(3, TimeUnit.SECONDS),
                "worker must terminate promptly after cancellation");
        assertTrue(observedCancelled.get(),
                "worker must observe the cancelled budget via TimeBudgetContext");
        assertEquals("public_request_deadline_exhausted",
                TraceStore.context().get("rag.endpoint.reason"));
        assertEquals(true, TraceStore.context().get("rag.endpoint.cancelRequested"));
        assertEquals(true, TraceStore.context().get("rag.endpoint.workerStarted"));
    }

    @Test
    void queuedTaskIsCancelledBeforeItStarts() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);

        // Occupy the single worker thread so the RAG task stays queued.
        CountDownLatch blockerGate = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        executor.submit(() -> {
            blockerStarted.countDown();
            blockerGate.await(3, TimeUnit.SECONDS);
            return null;
        });
        blockerStarted.await(2, TimeUnit.SECONDS);

        TimeBudgetContext.set(new TimeBudget(300));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> controller.query(request()));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        blockerGate.countDown();

        verify(facade, never()).query(any());
        assertEquals(true, TraceStore.context().get("rag.endpoint.cancelRequested"));
        assertEquals(true, TraceStore.context().get("rag.endpoint.cancelAccepted"));
        assertEquals("not_started", TraceStore.context().get("rag.endpoint.workerTermination"));
    }

    @Test
    void healthyBudgetCompletesNormally() {
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);
        QueryResponse expected = new QueryResponse();
        when(facade.query(any())).thenReturn(expected);

        TimeBudgetContext.set(new TimeBudget(10_000));

        QueryResponse result = controller.query(request());
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
        verify(facade).query(any());
    }
}
