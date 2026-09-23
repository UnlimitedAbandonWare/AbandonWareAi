package ai.abandonware.nova.orch.adapters;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyMode;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaAnalyzeWebSearchRetrieverTimeoutTraceTest {
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void plannedAndOriginalFallbackShareRequestDeadline() throws Exception {
        DeadlineBlockingProvider provider = new DeadlineBlockingProvider("planned deadline query");
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                provider,
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned deadline query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(250);
        TimeBudgetContext.set(new TimeBudget(100));

        long startedNs = System.nanoTime();
        List<Content> out;
        try {
            out = retriever.retrieve(QueryUtils.buildQuery(
                    "original deadline query",
                    Map.of("searchPolicyMode", "OFF")));
        } finally {
            provider.release.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        assertTrue(elapsedMs < 220L, "the request budget must cap the whole operation");
        assertEquals(1, provider.plannedCalls.get());
        assertEquals(0, provider.originalFallbackCalls.get());
        assertTrue(out.isEmpty());
    }

    @Test
    void interruptIgnoringSearchesRetainAdmissionAndExpireNewCallerAtDeadline() throws Exception {
        OccupancyProvider provider = new OccupancyProvider("planned occupied query", 2);
        executor = Executors.newFixedThreadPool(2);
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                provider,
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned occupied query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(250);
        ReflectionTestUtils.invokeMethod(retriever, "setSearchAdmissionLimitForTest", 2);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<Content>> first = callers.submit(() -> retriever.retrieve(
                    QueryUtils.buildQuery("first caller", Map.of("searchPolicyMode", "OFF"))));
            Future<List<Content>> second = callers.submit(() -> retriever.retrieve(
                    QueryUtils.buildQuery("second caller", Map.of("searchPolicyMode", "OFF"))));

            assertTrue(provider.entered.await(1, TimeUnit.SECONDS));
            assertTrue(first.get(1, TimeUnit.SECONDS).isEmpty());
            assertTrue(second.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals(2L, provider.workerExited.getCount(),
                    "caller timeout must not claim worker termination");

            List<Content> saturated = retriever.retrieve(QueryUtils.buildQuery(
                    "third caller",
                    Map.of("searchPolicyMode", "OFF")));

            assertTrue(saturated.isEmpty());
            assertEquals("request_deadline_exhausted", TraceStore.get("nova.search.deadline.reason"));
            assertEquals("routing_plan", TraceStore.get("nova.search.deadline.stage"));
            assertEquals(2, provider.plannedCalls.get());
            assertEquals(0, provider.originalFallbackCalls.get());
        } finally {
            provider.release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void routingPlanExceptionUsesClassifiedOriginalFallback() {
        String rawQuery = "routing failure ownerToken=fake-token";
        AtomicInteger providerCalls = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new OriginalFallbackProvider(rawQuery, providerCalls),
                (QueryContextPreprocessor) original -> original,
                new ThrowingRoutingPlanService(),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(500);
        TimeBudgetContext.set(new TimeBudget(500));

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                rawQuery,
                Map.of("searchPolicyMode", "OFF")));

        assertEquals(1, out.size());
        assertEquals(1, providerCalls.get());
        assertEquals("routing_plan_failed", TraceStore.get("nova.search.plan.failureReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void saturatedCallerRunsExecutorNeverRunsNovaWorkOnRequestThread() throws Exception {
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        CountDownLatch releasePlanner = new CountDownLatch(1);
        BlockingRoutingPlanService planner = new BlockingRoutingPlanService(releasePlanner);
        AtomicInteger providerCalls = new AtomicInteger();
        ThreadPoolExecutor callerRunsExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor = callerRunsExecutor;
        callerRunsExecutor.execute(() -> {
            executorOccupied.countDown();
            awaitReleaseIgnoringInterrupt(releaseExecutor);
        });
        assertTrue(executorOccupied.await(1, TimeUnit.SECONDS));

        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new OriginalFallbackProvider("caller-runs query", providerCalls),
                (QueryContextPreprocessor) original -> original,
                planner,
                new SearchPolicyEngine(),
                callerRunsExecutor,
                new ObjectMapper());
        retriever.setTimeoutMs(250);
        TimeBudgetContext.set(new TimeBudget(100));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        AtomicReference<Object> admissionReason = new AtomicReference<>();
        AtomicReference<Object> admissionStage = new AtomicReference<>();
        try {
            Future<List<Content>> result = caller.submit(() -> {
                List<Content> out = retriever.retrieve(
                        QueryUtils.buildQuery("caller-runs query", Map.of("searchPolicyMode", "OFF")));
                admissionReason.set(TraceStore.get("nova.search.admission.reason"));
                admissionStage.set(TraceStore.get("nova.search.admission.stage"));
                return out;
            });

            assertTrue(result.get(500, TimeUnit.MILLISECONDS).isEmpty());
            assertEquals(0, planner.calls.get(), "routing must not execute through CallerRunsPolicy");
            assertEquals(0, providerCalls.get());
            assertEquals("executor_saturated", admissionReason.get());
            assertEquals("routing_plan", admissionStage.get());
        } finally {
            releasePlanner.countDown();
            releaseExecutor.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedRetrieveRestoresCallerInterruptStatus() throws Exception {
        InterruptBlockingRoutingPlanService planner = new InterruptBlockingRoutingPlanService();
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new OriginalFallbackProvider("interrupted query", new AtomicInteger()),
                (QueryContextPreprocessor) original -> original,
                planner,
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(2_000);

        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<List<Content>> result = new AtomicReference<>();
        AtomicReference<Object> interruptedReason = new AtomicReference<>();
        CountDownLatch callerDone = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            result.set(retriever.retrieve(
                    QueryUtils.buildQuery("interrupted query", Map.of("searchPolicyMode", "OFF"))));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            interruptedReason.set(TraceStore.get("web.await.analyze.interrupted.reason"));
            callerDone.countDown();
        }, "nova-interrupted-caller");

        try {
            caller.start();
            assertTrue(planner.entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerDone.await(1, TimeUnit.SECONDS));

            assertTrue(result.get().isEmpty());
            assertTrue(interruptPreserved.get(), "retrieve must restore the caller interrupt flag");
            assertEquals("cancelled", interruptedReason.get());
        } finally {
            planner.release.countDown();
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(caller.isAlive());
        }
    }

    @Test
    void fairAdmissionDoesNotLetLaterWaiterCutIn() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new OriginalFallbackProvider("unused", new AtomicInteger()),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of()),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        ReflectionTestUtils.invokeMethod(retriever, "setSearchAdmissionLimitForTest", 1);

        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AutoCloseable heldLease = (AutoCloseable) ReflectionTestUtils.invokeMethod(
                retriever, "tryAcquireSearchLease", deadlineNs);
        assertTrue(heldLease != null);
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(retriever, "searchAdmissionPermits");
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService waiters = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = waiters.submit(() -> {
                try (AutoCloseable lease = (AutoCloseable) ReflectionTestUtils.invokeMethod(
                        retriever, "tryAcquireSearchLease", deadlineNs)) {
                    assertTrue(lease != null);
                    order.add("first");
                    firstAcquired.countDown();
                    assertTrue(releaseFirst.await(1, TimeUnit.SECONDS));
                }
                return null;
            });
            assertTrue(awaitQueueLength(permits, 1, 1, TimeUnit.SECONDS));

            Future<?> second = waiters.submit(() -> {
                try (AutoCloseable lease = (AutoCloseable) ReflectionTestUtils.invokeMethod(
                        retriever, "tryAcquireSearchLease", deadlineNs)) {
                    assertTrue(lease != null);
                    order.add("second");
                    secondAcquired.countDown();
                }
                return null;
            });
            assertTrue(awaitQueueLength(permits, 2, 1, TimeUnit.SECONDS));

            heldLease.close();
            heldLease = null;
            assertTrue(firstAcquired.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("first"), List.copyOf(order));
            releaseFirst.countDown();
            assertTrue(secondAcquired.await(1, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertEquals(List.of("first", "second"), List.copyOf(order));
        } finally {
            releaseFirst.countDown();
            if (heldLease != null) {
                heldLease.close();
            }
            waiters.shutdownNow();
            assertTrue(waiters.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void hardTimeoutCancellationEmitsRedactedAwaitEventWithoutInterruptingWorker() throws Exception {
        String rawQuery = "raw analyze timeout query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        BlockingProvider provider = new BlockingProvider("planned slow query");
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                provider,
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned slow query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());
        retriever.setTimeoutMs(1);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                rawQuery,
                Map.of("searchPolicyMode", "OFF")));
        assertTrue(provider.entered.await(1, TimeUnit.SECONDS));
        provider.release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        assertTrue(out.isEmpty());
        assertFalse(provider.interrupted.get());
        assertEquals(1, provider.calls.get(),
                "an exhausted whole-operation deadline must skip original fallback");
        assertEquals(1L, TraceStore.getLong("web.await.cancelSuppressed"));
        assertEquals("timeout_hard", TraceStore.get("web.await.cancelSuppressed.reason"));
        assertEquals(1L, TraceStore.getLong("web.await.analyze.cancelAttempted"));
        assertEquals(1L, TraceStore.getLong("web.await.analyze.cancelSucceeded"));
        assertEquals(250L, TraceStore.getLong("web.await.analyze.timeoutMs"));
        assertTrue(String.valueOf(TraceStore.get("web.await.analyze.queryHash12")).matches("[0-9a-f]{12}"));

        Object eventsObj = TraceStore.get("web.await.events");
        assertTrue(eventsObj instanceof List<?>);
        Map<String, Object> event = (Map<String, Object>) ((List<?>) eventsObj).get(0);
        assertEquals("NovaAnalyze", event.get("engine"));
        assertEquals("timeout_hard", event.get("cause"));
        assertEquals(Boolean.TRUE, event.get("timeout"));
        assertEquals(Boolean.TRUE, event.get("hardTimeout"));
        assertEquals(Boolean.TRUE, event.get("cancelSuppressed"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void policyDecisionFailureLeavesRedactedTraceAndContinuesSearch() {
        String rawQuery = "policy failure query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new FixedProvider(List.of("policy fallback result")),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned policy query")),
                new ThrowingSearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals("exception", TraceStore.get("web.analyze.searchPolicy.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.searchPolicy.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.searchPolicy.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void policyApplyFailureLeavesRedactedTraceAndUsesBasePlan() {
        String rawQuery = "policy apply query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new FixedProvider(List.of("base plan result")),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned base query")),
                new ThrowingPolicyApplyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals("apply", TraceStore.get("web.analyze.searchPolicy.stage"));
        assertEquals("exception", TraceStore.get("web.analyze.searchPolicy.failureReason"));
        assertEquals("IllegalArgumentException", TraceStore.get("web.analyze.searchPolicy.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void plannedSearchFailureLeavesRedactedTraceAndUsesOriginalFallback() {
        String rawQuery = "planned fallback query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingPlannedProvider("planned failing query", rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned failing query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.plannedSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.plannedSearch.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.plannedSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.plannedSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals("planned failing query".length(), TraceStore.get("web.analyze.plannedSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void partialFutureFailureLeavesRedactedTraceAndUsesOriginalFallback() {
        String rawQuery = "partial future query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingErrorProvider("planned error query", rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned error query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.partialSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.partialSearch.failureReason"));
        assertEquals("AssertionError", TraceStore.get("web.analyze.partialSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.partialSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("web.analyze.partialSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void fallbackSearchFailureLeavesRedactedTraceAndReturnsEmpty() {
        String rawQuery = "fallback analyze query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        executor = Executors.newSingleThreadExecutor();
        NovaAnalyzeWebSearchRetriever retriever = new NovaAnalyzeWebSearchRetriever(
                null,
                new ThrowingOriginalFallbackProvider(rawQuery),
                (QueryContextPreprocessor) original -> original,
                new FixedRoutingPlanService(List.of("planned empty query")),
                new SearchPolicyEngine(),
                executor,
                new ObjectMapper());

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

        assertTrue(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.fallbackSearch.failed"));
        assertEquals("exception", TraceStore.get("web.analyze.fallbackSearch.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.analyze.fallbackSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.analyze.fallbackSearch.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(rawQuery.length(), TraceStore.get("web.analyze.fallbackSearch.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void invalidMetaIntegerFallbackUsesStableReasonCodeWithoutRawValue() {
        Integer out = ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "metaInt",
                Map.of("webTopK", "private topK value"),
                "webTopK",
                7);

        assertEquals(7, out);
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.metaInt.parseFallback"));
        assertEquals("webTopK", TraceStore.get("web.analyze.metaInt.parseFallback.key"));
        assertEquals("invalid_number", TraceStore.get("web.analyze.metaInt.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private topK value"));
    }

    @Test
    void nonFiniteMetaIntegerFallbackUsesStableReasonCode() {
        Integer out = ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "metaInt",
                Map.of("webTopK", Double.POSITIVE_INFINITY),
                "webTopK",
                7);

        assertEquals(7, out);
        assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.metaInt.parseFallback"));
        assertEquals("webTopK", TraceStore.get("web.analyze.metaInt.parseFallback.key"));
        assertEquals("invalid_number", TraceStore.get("web.analyze.metaInt.parseFallback.errorType"));
    }

    @Test
    void interruptedAndCancelledTraceUsesStableReasonCodeWithoutRawQuery() {
        String rawQuery = "private analyze interrupted query ownerToken=fake-token";

        ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "traceInterruptedPoll",
                rawQuery,
                new InterruptedException("ownerToken=fake-token"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.interrupted.reason"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.interrupted.errorType"));

        ReflectionTestUtils.invokeMethod(
                NovaAnalyzeWebSearchRetriever.class,
                "traceCancelFailure",
                rawQuery,
                new CancellationException("ownerToken=fake-token"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.cancelFailure.reason"));
        assertEquals("cancelled", TraceStore.get("web.await.analyze.cancelFailure.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    private static final class FixedRoutingPlanService extends RoutingPlanService {
        private final List<String> queries;

        private FixedRoutingPlanService(List<String> queries) {
            super(null, null, null);
            this.queries = queries;
        }

        @Override
        public List<String> plan(String userPrompt, String assistantDraft, int maxQueries) {
            return queries;
        }
    }

    private static final class ThrowingRoutingPlanService extends RoutingPlanService {
        private ThrowingRoutingPlanService() {
            super(null, null, null);
        }

        @Override
        public List<String> plan(String userPrompt, String assistantDraft, int maxQueries) {
            throw new IllegalStateException("raw routing ownerToken=fake-token");
        }
    }

    private static final class BlockingRoutingPlanService extends RoutingPlanService {
        private final CountDownLatch release;
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingRoutingPlanService(CountDownLatch release) {
            super(null, null, null);
            this.release = release;
        }

        @Override
        public List<String> plan(String userPrompt, String assistantDraft, int maxQueries) {
            calls.incrementAndGet();
            awaitReleaseIgnoringInterrupt(release);
            return List.of(userPrompt);
        }
    }

    private static final class InterruptBlockingRoutingPlanService extends RoutingPlanService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private InterruptBlockingRoutingPlanService() {
            super(null, null, null);
        }

        @Override
        public List<String> plan(String userPrompt, String assistantDraft, int maxQueries) {
            entered.countDown();
            awaitReleaseIgnoringInterrupt(release);
            return List.of(userPrompt);
        }
    }

    private static final class DeadlineBlockingProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger plannedCalls = new AtomicInteger();
        private final AtomicInteger originalFallbackCalls = new AtomicInteger();

        private DeadlineBlockingProvider(String plannedQuery) {
            this.plannedQuery = plannedQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (!plannedQuery.equals(query)) {
                originalFallbackCalls.incrementAndGet();
                return List.of("unexpected fresh fallback");
            }
            plannedCalls.incrementAndGet();
            awaitReleaseIgnoringInterrupt(release);
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "deadline-blocking";
        }
    }

    private static final class OccupancyProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch workerExited;
        private final AtomicInteger plannedCalls = new AtomicInteger();
        private final AtomicInteger originalFallbackCalls = new AtomicInteger();

        private OccupancyProvider(String plannedQuery, int workers) {
            this.plannedQuery = plannedQuery;
            this.entered = new CountDownLatch(workers);
            this.workerExited = new CountDownLatch(workers);
        }

        @Override
        public List<String> search(String query, int topK) {
            if (!plannedQuery.equals(query)) {
                originalFallbackCalls.incrementAndGet();
                return List.of();
            }
            plannedCalls.incrementAndGet();
            entered.countDown();
            try {
                awaitReleaseIgnoringInterrupt(release);
                return List.of();
            } finally {
                workerExited.countDown();
            }
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "occupancy-blocking";
        }
    }

    private static final class OriginalFallbackProvider implements WebSearchProvider {
        private final String originalQuery;
        private final AtomicInteger calls;

        private OriginalFallbackProvider(String originalQuery, AtomicInteger calls) {
            this.originalQuery = originalQuery;
            this.calls = calls;
        }

        @Override
        public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            return originalQuery.equals(query)
                    ? List.of("classified original fallback")
                    : List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "original-fallback";
        }
    }

    private static void awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (release.getCount() > 0L) {
                try {
                    if (release.await(2, TimeUnit.SECONDS)) {
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

    private static boolean awaitQueueLength(
            Semaphore semaphore,
            int expected,
            long timeout,
            TimeUnit unit) {
        long deadlineNs = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNs) {
            if (semaphore != null && semaphore.getQueueLength() >= expected) {
                return true;
            }
            Thread.onSpinWait();
        }
        return semaphore != null && semaphore.getQueueLength() >= expected;
    }

    private static final class BlockingProvider implements WebSearchProvider {
        private final String blockedQuery;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingProvider(String blockedQuery) {
            this.blockedQuery = blockedQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            if (!blockedQuery.equals(query)) {
                return List.of();
            }
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                TraceStore.put("test.novaAnalyzeWebSearch.blockingProvider.interrupted", true);
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "blocking";
        }
    }

    private static final class FixedProvider implements WebSearchProvider {
        private final List<String> results;

        private FixedProvider(List<String> results) {
            this.results = results;
        }

        @Override
        public List<String> search(String query, int topK) {
            return results;
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "fixed";
        }
    }

    private static final class ThrowingOriginalFallbackProvider implements WebSearchProvider {
        private final String originalQuery;

        private ThrowingOriginalFallbackProvider(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (originalQuery.equals(query)) {
                throw new IllegalStateException("raw fallback api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-original";
        }
    }

    private static final class ThrowingPlannedProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final String originalQuery;

        private ThrowingPlannedProvider(String plannedQuery, String originalQuery) {
            this.plannedQuery = plannedQuery;
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (plannedQuery.equals(query)) {
                throw new IllegalStateException("raw planned api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            if (originalQuery.equals(query)) {
                return List.of("original fallback result");
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-planned";
        }
    }

    private static final class ThrowingErrorProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final String originalQuery;

        private ThrowingErrorProvider(String plannedQuery, String originalQuery) {
            this.plannedQuery = plannedQuery;
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (plannedQuery.equals(query)) {
                throw new AssertionError("raw partial api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            if (originalQuery.equals(query)) {
                return List.of("original fallback after future failure");
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-error";
        }
    }

    private static final class ThrowingSearchPolicyEngine extends SearchPolicyEngine {
        @Override
        public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
            throw new IllegalStateException("raw policy failure api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "");
        }
    }

    private static final class ThrowingPolicyApplyEngine extends SearchPolicyEngine {
        @Override
        public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
            return new SearchPolicyDecision(
                    SearchPolicyMode.BALANCED,
                    true,
                    true,
                    4,
                    2,
                    1,
                    2,
                    1,
                    1.0d,
                    1.0d,
                    "test-policy");
        }

        @Override
        public List<String> apply(List<String> basePlanned, String originalQuery, SearchPolicyDecision d) {
            throw new IllegalArgumentException("raw policy apply api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "");
        }
    }
}
