package com.example.lms.service.rag.langgraph;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.example.lms.service.rag.langgraph.RagGraphExecutorTest.response;
import static org.junit.jupiter.api.Assertions.*;

class RagOrchestratorFacadeInterruptionTest {
    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void terminalCallerInterruptionDoesNotRestartLegacyRetrieval(boolean throughFacade,
                                                                boolean interruptCaller) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicInteger graphCalls = new AtomicInteger();
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicReference<QueryResponse> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean callerInterruptPreserved = new AtomicBoolean();
        ExecutorService runtime = Executors.newSingleThreadExecutor();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        properties.setTimeoutMs(30_000);
        UnifiedRagOrchestrator graphOwner = new UnifiedRagOrchestrator() {
            @Override public QueryTrace queryWithTrace(QueryRequest request) {
                graphCalls.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture release timeout");
                    QueryTrace trace = new QueryTrace();
                    trace.response = response("graph", request);
                    trace.finalResults = trace.response.results;
                    return trace;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("unexpected graph worker interruption", interrupted);
                } finally {
                    workerFinished.countDown();
                }
            }
        };
        UnifiedRagOrchestrator legacy = new UnifiedRagOrchestrator() {
            @Override public QueryResponse query(QueryRequest request) {
                legacyCalls.incrementAndGet();
                return response("legacy", request);
            }
        };
        RagGraphExecutor graph = new RagGraphExecutor(graphOwner,
                new RagGraphExecutorTest.FixedProvider<>(null), properties,
                new RagGraphExecutorTest.FixedProvider<>(null), runtime);
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        QueryRequest request = new QueryRequest();
        request.query = "bounded fictional graph request";
        request.planId = "safe_autorun.v1";
        Thread caller = new Thread(() -> {
            try {
                result.set(throughFacade ? facade.query(request) : graph.execute(request));
            } catch (Throwable caught) {
                failure.set(caught);
            } finally {
                callerInterruptPreserved.set(Thread.currentThread().isInterrupted());
                Thread.interrupted();
                TraceStore.clear();
                TimeBudgetContext.clear();
                GuardContextHolder.clear();
                MDC.clear();
            }
        }, "audit100-graph-caller-fixture");
        try {
            caller.start();
            assertTrue(started.await(10, TimeUnit.SECONDS), "real graph worker must enter retrieval");
            if (interruptCaller) caller.interrupt(); else release.countDown();
            caller.join(10_000);
            assertFalse(caller.isAlive(), "caller must terminate within the fixture bound");
            assertEquals(1, graphCalls.get());
            assertEquals(interruptCaller, callerInterruptPreserved.get());
            if (interruptCaller) {
                assertAll(
                        () -> assertEquals(0, legacyCalls.get(), "terminal caller interruption must not restart legacy retrieval"),
                        () -> assertInstanceOf(CancellationException.class, failure.get()),
                        () -> assertNull(result.get()));
                assertInstanceOf(InterruptedException.class, failure.get().getCause());
                assertEquals(1L, workerFinished.getCount(), "cancel(false) does not prove graph worker termination");
            } else {
                assertNull(failure.get());
                assertEquals("graph", result.get().requestId);
                assertEquals(0, legacyCalls.get());
            }
        } finally {
            release.countDown();
            caller.join(10_000);
            runtime.shutdownNow();
            assertTrue(runtime.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(caller.isAlive());
            assertTrue(workerFinished.await(10, TimeUnit.SECONDS));
        }
    }
}
