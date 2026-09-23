package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.config.SearchExecutorConfig;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.langgraph.RagGraphExecutor;
import com.example.lms.service.rag.langgraph.RagGraphProperties;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagOrchestratorControllerTest {

    private final List<ExecutorService> ownedExecutors = new ArrayList<>();

    @AfterEach
    void tearDownEndpointExecutors() throws Exception {
        TimeBudgetContext.clear();
        TraceStore.clear();
        for (ExecutorService executor : ownedExecutors) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        ownedExecutors.clear();
    }

    @Test
    void queryEndpointDelegatesRequestBodyThroughFacade() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"langgraph endpoint\",\"planId\":\"probe.search.v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("legacy"))
                .andExpect(jsonPath("$.planApplied").value("probe.search.v1"))
                .andExpect(jsonPath("$['debug']['langgraph.mode']").value("off"))
                .andExpect(jsonPath("$['debug']['rag.eval.queryFingerprint']['queryHash']").value("hash-only"))
                .andExpect(jsonPath("$['debug']['rag.eval.thresholdBreaks'][0]['label']")
                        .value("after_filter_starvation"));

        assertEquals(1, legacy.calls);
        assertEquals("langgraph endpoint", legacy.lastRequest.query);
        assertEquals("probe.search.v1", legacy.lastRequest.planId);
    }

    @Test
    void probeEndpointBuildsSaferProbeRequest() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        mvc.perform(post("/api/rag/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"probe langgraph\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("legacy"))
                .andExpect(jsonPath("$.planApplied").value("safe_autorun.v1"))
                .andExpect(jsonPath("$['debug']['langgraph.mode']").value("off"))
                .andExpect(jsonPath("$['debug']['rag.eval.queryFingerprint']['queryHash']").value("hash-only"));

        assertEquals(1, legacy.calls);
        assertEquals("probe langgraph", legacy.lastRequest.query);
        assertTrue(legacy.lastRequest.enableSelfAsk);
        assertFalse(legacy.lastRequest.whitelistOnly);
    }

    @Test
    void probeRejectsNonStringQueryShapesBeforeFacade() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy)).build();

        for (String body : new String[]{"{\"q\":7}", "{\"q\":[]}", "{\"q\":{}}"}) {
            mvc.perform(post("/api/rag/probe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reasonCode").value("rag_body_invalid"));
        }

        assertEquals(0, legacy.calls);
    }

    @Test
    void queryRejectsMissingBlankAndOversizedInputBeforeFacade() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        RagOrchestratorController controller = controller(legacy);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/rag/query").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("rag_query_required"));
        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + "q".repeat(8_193) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reasonCode").value("rag_query_too_large"));
        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"fanout\",\"enableSelfAsk\":true,"
                                + "\"deepResearch\":true,\"aggressive\":true}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reasonCode").value("rag_retrieval_budget_exceeded"));

        assertEquals(0, legacy.calls);
        PublicRequestBudgetGuard.Rejection rejection = assertThrows(
                PublicRequestBudgetGuard.Rejection.class, () -> controller.query(null));
        assertEquals("rag_query_required", rejection.reasonCode());
    }

    @Test
    void malformedQueryJsonUsesStableReasonAndNeverCallsFacade() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        MockMvc mvc = standaloneSetup(controller(legacy)).build();

        mvc.perform(post("/api/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\",\"topK\":2147483648}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("rag_body_invalid"));

        assertEquals(0, legacy.calls);
    }

    @Test
    void primaryWildGraphExpansionRejectsBeforeFacade() {
        FakeOrchestrator legacy = new FakeOrchestrator();
        RagOrchestratorController controller = controller(legacy);
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "ragOrchestratorMode", "PRIMARY");
        guard.setMaxRetrievalWork(39);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "publicRequestBudgetGuard", guard);
        QueryRequest request = new QueryRequest();
        request.query = "q";
        request.planId = "wild";
        request.topK = 1;
        request.useWeb = true;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;

        PublicRequestBudgetGuard.Rejection rejection = assertThrows(
                PublicRequestBudgetGuard.Rejection.class, () -> controller.query(request));

        assertEquals("rag_retrieval_budget_exceeded", rejection.reasonCode());
        assertEquals(0, legacy.calls);
    }

    @Test
    void healthyRequestRunsOnDedicatedEndpointWorker() throws Exception {
        FakeOrchestrator legacy = new FakeOrchestrator();
        ExecutorService runtime = newEndpointRuntime(1, 0);
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(
                legacy,
                (RagGraphExecutor) null,
                properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        mvc.perform(post("/api/rag/query")
                        .header("X-Budget-Ms", "1_000".replace("_", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"bounded endpoint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("legacy"));

        assertTrue(legacy.threadName.get().startsWith("awx-rag-endpoint-"));
    }

    @Test
    void springWiringInjectsEndpointExecutorInsteadOfGraphExecutor() {
        FakeOrchestrator legacy = new FakeOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(
                legacy,
                (RagGraphExecutor) null,
                properties);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SearchExecutorConfig.class, RagOrchestratorController.class);
            context.registerBean(RagOrchestratorFacade.class, () -> facade);
            context.refresh();

            RagOrchestratorController controller = context.getBean(RagOrchestratorController.class);
            ExecutorService endpoint = context.getBean("ragEndpointExecutor", ExecutorService.class);
            ExecutorService graph = context.getBean("ragGraphWorkerExecutor", ExecutorService.class);
            Object injected = ReflectionTestUtils.getField(controller, "endpointExecutor");
            assertSame(endpoint, injected);
            assertNotSame(graph, injected);

            QueryRequest request = new QueryRequest();
            request.query = "spring wiring";
            TimeBudgetContext.set(new TimeBudget(1_000));
            QueryResponse response = controller.query(request);

            assertEquals("legacy", response.requestId);
            assertTrue(legacy.threadName.get().startsWith("awx-rag-endpoint-"));
        } finally {
            TimeBudgetContext.clear();
        }
    }

    @ParameterizedTest
    @EnumSource(value = RagGraphProperties.Mode.class, names = {"OFF", "SHADOW"})
    void blockingLegacyModesReturnFixed408WithoutClaimingWorkerTermination(
            RagGraphProperties.Mode mode) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BlockingOrchestrator legacy = new BlockingOrchestrator(started, release, finished);
        ExecutorService runtime = newEndpointRuntime(1, 0);
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(mode);
        RagGraphExecutor graph = mock(RagGraphExecutor.class);
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        try {
            mvc.perform(post("/api/rag/query")
                            .header("X-Budget-Ms", "100")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"blocking legacy mode\"}"))
                    .andExpect(status().isRequestTimeout())
                    .andExpect(jsonPath("$.reasonCode").value("public_request_deadline_exhausted"));

            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(legacy.threadName.get().startsWith("awx-rag-endpoint-"));
            assertEquals(true, TraceStore.get("rag.endpoint.cancelRequested"));
            assertEquals(false, TraceStore.get("rag.endpoint.workerFinished"));
            assertEquals("unfinished", TraceStore.get("rag.endpoint.workerTermination"));
            assertEquals(1L, finished.getCount());
            verifyNoInteractions(graph);
        } finally {
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void primaryGraphFailureThenBlockingLegacyFallbackReturnsFixed408() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BlockingOrchestrator legacy = new BlockingOrchestrator(started, release, finished);
        ExecutorService runtime = newEndpointRuntime(1, 0);
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        RagGraphExecutor graph = mock(RagGraphExecutor.class);
        when(graph.execute(any(QueryRequest.class))).thenThrow(new IllegalStateException("graph unavailable"));
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        try {
            mvc.perform(post("/api/rag/query")
                            .header("X-Budget-Ms", "100")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"primary fallback deadline\"}"))
                    .andExpect(status().isRequestTimeout())
                    .andExpect(jsonPath("$.reasonCode").value("public_request_deadline_exhausted"));

            assertTrue(started.await(1, TimeUnit.SECONDS));
            verify(graph).execute(any(QueryRequest.class));
            assertEquals(1, legacy.calls.get());
            assertEquals(false, TraceStore.get("rag.endpoint.workerFinished"));
            assertEquals("unfinished", TraceStore.get("rag.endpoint.workerTermination"));
        } finally {
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void saturatedEndpointExecutorReturnsFixed429WithoutInlineFacadeWork() throws Exception {
        ExecutorService runtime = newEndpointRuntime(1, 0);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.execute(() -> {
            occupied.countDown();
            awaitReleaseIgnoringInterrupt(release);
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        FakeOrchestrator legacy = new FakeOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(
                legacy,
                (RagGraphExecutor) null,
                properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        try {
            mvc.perform(post("/api/rag/query")
                            .header("X-Budget-Ms", "1000")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"saturated endpoint\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.reasonCode").value("rag_endpoint_executor_saturated"));

            assertEquals(0, legacy.calls);
            assertEquals("rag_endpoint_executor_saturated", TraceStore.get("rag.endpoint.reason"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedRequestCancelledAtDeadlineNeverRunsFacadeLater() throws Exception {
        ExecutorService runtime = newEndpointRuntime(1, 1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.execute(() -> {
            occupied.countDown();
            awaitReleaseIgnoringInterrupt(release);
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        FakeOrchestrator legacy = new FakeOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(
                legacy,
                (RagGraphExecutor) null,
                properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();

        try {
            mvc.perform(post("/api/rag/query")
                            .header("X-Budget-Ms", "100")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"queued deadline\"}"))
                    .andExpect(status().isRequestTimeout())
                    .andExpect(jsonPath("$.reasonCode").value("public_request_deadline_exhausted"));

            assertEquals(0, legacy.calls);
            assertEquals(false, TraceStore.get("rag.endpoint.workerStarted"));
            assertEquals("not_started", TraceStore.get("rag.endpoint.workerTermination"));
        } finally {
            release.countDown();
            runtime.shutdown();
            assertTrue(runtime.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, legacy.calls);
    }

    @Test
    void interruptedCallerGetsFixed408AndKeepsInterruptFlag() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BlockingOrchestrator legacy = new BlockingOrchestrator(started, release, finished);
        ExecutorService runtime = newEndpointRuntime(1, 0);
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(
                legacy,
                (RagGraphExecutor) null,
                properties);
        MockMvc mvc = standaloneSetup(asyncController(facade, runtime))
                .addFilters(new PublicRequestBudgetGuard())
                .build();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicInteger responseStatus = new AtomicInteger();
        AtomicReference<String> responseBody = new AtomicReference<>("");
        AtomicBoolean interruptPreserved = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                var result = mvc.perform(post("/api/rag/query")
                                .header("X-Budget-Ms", "5000")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"caller interruption\"}"))
                        .andReturn();
                responseStatus.set(result.getResponse().getStatus());
                responseBody.set(result.getResponse().getContentAsString());
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        }, "rag-endpoint-test-caller");

        try {
            caller.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000L);

            assertFalse(caller.isAlive());
            assertEquals(null, callerFailure.get());
            assertEquals(408, responseStatus.get());
            assertTrue(responseBody.get().contains("public_request_cancelled"));
            assertTrue(interruptPreserved.get());
        } finally {
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(1_000L);
            }
        }
    }

    private static RagOrchestratorController controller(FakeOrchestrator legacy) {
        RagGraphProperties properties = new RagGraphProperties();
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, (RagGraphExecutor) null, properties);
        return new RagOrchestratorController(facade);
    }

    private ExecutorService newEndpointRuntime(int workers, int queueCapacity) {
        SearchExecutorConfig config = new SearchExecutorConfig();
        ReflectionTestUtils.setField(config, "ragEndpointWorkers", workers);
        ReflectionTestUtils.setField(config, "ragEndpointQueueCapacity", queueCapacity);
        ExecutorService executor = ReflectionTestUtils.invokeMethod(config, "ragEndpointExecutor");
        assertTrue(executor != null);
        ownedExecutors.add(executor);
        return executor;
    }

    private static RagOrchestratorController asyncController(
            RagOrchestratorFacade facade,
            ExecutorService runtime) throws Exception {
        Constructor<RagOrchestratorController> constructor =
                RagOrchestratorController.class.getConstructor(
                        RagOrchestratorFacade.class,
                        ExecutorService.class);
        return constructor.newInstance(facade, runtime);
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private int calls;
        private QueryRequest lastRequest;
        private final AtomicReference<String> threadName = new AtomicReference<>("");

        @Override
        public QueryResponse query(QueryRequest req) {
            calls++;
            lastRequest = req;
            threadName.set(Thread.currentThread().getName());
            QueryResponse response = new QueryResponse();
            response.requestId = "legacy";
            response.planApplied = req == null ? null : req.planId;
            response.debug.put("rag.eval.queryFingerprint", java.util.Map.of(
                    "queryHash", "hash-only",
                    "length", req == null || req.query == null ? 0 : req.query.length(),
                    "tokenBucket", "1-4"));
            response.debug.put("rag.eval.thresholdBreaks", java.util.List.of(java.util.Map.of(
                    "label", "after_filter_starvation",
                    "comparator", "<=",
                    "severity", 1.0d,
                    "stage", "filter")));
            return response;
        }
    }

    private static final class BlockingOrchestrator extends UnifiedRagOrchestrator {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final CountDownLatch finished;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> threadName = new AtomicReference<>("");

        private BlockingOrchestrator(
                CountDownLatch started,
                CountDownLatch release,
                CountDownLatch finished) {
            this.started = started;
            this.release = release;
            this.finished = finished;
        }

        @Override
        public QueryResponse query(QueryRequest req) {
            calls.incrementAndGet();
            threadName.set(Thread.currentThread().getName());
            started.countDown();
            try {
                awaitReleaseIgnoringInterrupt(release);
                QueryResponse response = new QueryResponse();
                response.requestId = "legacy-blocking";
                response.planApplied = req == null ? null : req.planId;
                return response;
            } finally {
                finished.countDown();
            }
        }
    }

    private static void awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (release.getCount() > 0L) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
