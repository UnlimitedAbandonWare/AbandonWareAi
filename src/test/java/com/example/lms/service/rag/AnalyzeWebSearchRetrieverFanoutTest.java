package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import dev.langchain4j.rag.content.Content;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Reproduction/regression tests for the serial planned-query fanout in
 * {@link AnalyzeWebSearchRetriever#retrieve}.
 *
 * Baseline (flag off / unpatched): planned queries run strictly serially, so a
 * caller-side deadline cannot be met and bounded budget checks are absent.
 * Patched (gpt-search.analyze.fanout.enabled=true): planned queries run on a
 * small dedicated pool, inherit the shared TimeBudget deadline, and merge
 * deterministically in planned order.
 */
class AnalyzeWebSearchRetrieverFanoutTest {

    final WebSearchProvider provider = mock(WebSearchProvider.class);
    final QueryContextPreprocessor preprocessor = mock(QueryContextPreprocessor.class);
    final RoutingPlanService planner = mock(RoutingPlanService.class);
    final SearchPolicyEngine policy = mock(SearchPolicyEngine.class);

    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final ConcurrentLinkedQueue<String> calls = new ConcurrentLinkedQueue<>();
    volatile long perCallSleepMs = 300;
    volatile Map<String, Long> sleepByQuery = Map.of();

    AnalyzeWebSearchRetriever retriever() {
        when(preprocessor.enrich(anyString(), anyMap())).thenAnswer(inv -> inv.getArgument(0));
        when(policy.tuneTopK(anyInt(), isNull())).thenAnswer(call -> call.getArgument(0));
        when(planner.plan(anyString(), isNull(), anyInt()))
                .thenReturn(List.of("planned-q1", "planned-q2", "planned-q3", "planned-q4"));
        when(provider.search(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            calls.add(q);
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(sleepByQuery.getOrDefault(q, perCallSleepMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", ie);
            } finally {
                inFlight.decrementAndGet();
            }
            return List.of("doc-" + q);
        });
        return new AnalyzeWebSearchRetriever(null, provider, preprocessor, planner, policy, null);
    }

    @AfterEach
    void clear() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    /** Enables the flag-gated fanout; fails as RED while the feature is absent. */
    private void enableFanout(AnalyzeWebSearchRetriever r, int workers, int queueCapacity) {
        try {
            setField(r, "parallelFanoutEnabled", true);
            setField(r, "fanoutWorkers", workers);
            setField(r, "fanoutQueueCapacity", queueCapacity);
        } catch (NoSuchFieldException e) {
            fail("fanout flag not implemented: " + e.getMessage());
        } catch (IllegalAccessException e) {
            fail(e);
        }
    }

    private static void setField(Object target, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = AnalyzeWebSearchRetriever.class.getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Boolean b) f.setBoolean(target, b);
        else if (value instanceof Integer i) f.setInt(target, i);
        else f.set(target, value);
    }

    private static List<String> texts(List<Content> contents) {
        List<String> out = new ArrayList<>();
        for (Content c : contents) out.add(c.textSegment().text());
        return out;
    }

    // ------------------------------------------------------------------
    // 1. REPRODUCTION: serial fanout cannot meet a caller-side deadline and
    //    shows zero concurrency across planned queries.
    // ------------------------------------------------------------------
    @Test
    void fanoutRunsPlannedQueriesConcurrentlyWithinSharedBudget() throws Exception {
        var retriever = retriever();
        enableFanout(retriever, 4, 32);
        TimeBudgetContext.set(new TimeBudget(900));

        long started = System.nanoTime();
        List<Content> result = retriever.retrieve(QueryUtils.buildQuery("user question"));
        long wallMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(4, calls.size(), "every planned query must still be called");
        assertTrue(maxInFlight.get() >= 2,
                "expected concurrent provider calls, observed maxInFlight=" + maxInFlight.get());
        assertTrue(wallMs < 900, "shared deadline exceeded: wallMs=" + wallMs);
        assertEquals(List.of("doc-planned-q1", "doc-planned-q2", "doc-planned-q3", "doc-planned-q4"),
                texts(result), "merged evidence must keep deterministic planned order");
    }

    // ------------------------------------------------------------------
    // 2. BASELINE (flag off / current source): serial execution preserves all
    //    evidence and order, but blows through a caller deadline. This test
    //    passes before AND after the patch because the flag defaults off.
    // ------------------------------------------------------------------
    @Test
    void serialBaselineKeepsEvidenceButCannotMeetCallerDeadline() throws Exception {
        var retriever = retriever();
        perCallSleepMs = 300;

        var single = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fanout-test-caller");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = single.submit(() -> retriever.retrieve(QueryUtils.buildQuery("user question")));
            try {
                future.get(800, TimeUnit.MILLISECONDS);
                fail("serial fanout unexpectedly met the 800ms caller deadline");
            } catch (java.util.concurrent.TimeoutException expected) {
                // reproduction: caller gives up while serial calls continue
            }
            List<Content> result = future.get(10, TimeUnit.SECONDS);
            assertEquals(4, calls.size());
            assertEquals(1, maxInFlight.get(), "serial path must never overlap provider calls");
            assertEquals(List.of("doc-planned-q1", "doc-planned-q2", "doc-planned-q3", "doc-planned-q4"),
                    texts(result));
        } finally {
            single.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 3. Exhausted shared budget must stop new provider calls (zero-wait, not
    //    unlimited I/O). RED until the fanout path exists.
    // ------------------------------------------------------------------
    @Test
    void exhaustedSharedBudgetSkipsNewProviderCalls() {
        var retriever = retriever();
        enableFanout(retriever, 4, 32);
        TimeBudget exhausted = new TimeBudget(1);
        try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        TimeBudgetContext.set(exhausted);

        List<Content> result = retriever.retrieve(QueryUtils.buildQuery("user question"));

        assertTrue(result.isEmpty(), "expired budget must not start provider I/O");
        assertEquals(0, calls.size(), "provider must not be called after the deadline");
    }

    // ------------------------------------------------------------------
    // 4. Fail-soft isolation: one planned query failing must not drop the
    //    evidence returned by the others, and order stays deterministic.
    // ------------------------------------------------------------------
    @Test
    void oneFailingQueryDoesNotLoseCompletedEvidence() {
        var retriever = retriever();
        enableFanout(retriever, 4, 32);
        perCallSleepMs = 50;
        when(provider.search(eq("planned-q2"), anyInt())).thenThrow(new RuntimeException("provider-down"));

        List<Content> result = retriever.retrieve(QueryUtils.buildQuery("user question"));

        verify(provider, times(4)).search(anyString(), anyInt());
        assertEquals(List.of("doc-planned-q1", "doc-planned-q3", "doc-planned-q4"), texts(result),
                "completed evidence must be preserved in planned order");
    }

    // ------------------------------------------------------------------
    // 5. Deadline hit while workers are still running: return what completed
    //    inside the shared budget, stop waiting, record unfinished workers,
    //    and the pool must keep serving the next request (recovery).
    // ------------------------------------------------------------------
    @Test
    void deadlineCutoffReturnsCompletedEvidenceAndPoolRecovers() {
        var retriever = retriever();
        enableFanout(retriever, 4, 32);
        sleepByQuery = Map.of("planned-q1", 20L);

        TimeBudgetContext.set(new TimeBudget(120));
        long started = System.nanoTime();
        List<Content> cut = retriever.retrieve(QueryUtils.buildQuery("user question"));
        long wallMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(wallMs < 600, "must stop waiting at the shared deadline, wallMs=" + wallMs);
        assertEquals(List.of("doc-planned-q1"), texts(cut),
                "evidence completed inside the budget must be preserved");
        assertEquals(4, TraceStore.get("web.analyze.fanout.submitted"));
        assertEquals(true, TraceStore.get("web.analyze.fanout.deadlineReached"));
        int unfinished = (Integer) TraceStore.get("web.analyze.fanout.workersUnfinishedAtReturn");
        assertTrue(unfinished >= 1, "running workers must be reported, not silently dropped");

        // Recovery: a fresh budget on the same retriever still serves fully.
        sleepByQuery = Map.of();
        perCallSleepMs = 20;
        TimeBudgetContext.set(new TimeBudget(2000));
        List<Content> recovered = retriever.retrieve(QueryUtils.buildQuery("user question"));
        assertEquals(List.of("doc-planned-q1", "doc-planned-q2", "doc-planned-q3", "doc-planned-q4"),
                texts(recovered));
    }

    // ------------------------------------------------------------------
    // 6. Queued workers must not start provider I/O after the shared budget
    //    expired while they waited (pre-start check inside the task).
    // ------------------------------------------------------------------
    @Test
    void queuedTasksDoNotStartIoAfterBudgetExpiry() throws Exception {
        var retriever = retriever();
        enableFanout(retriever, 1, 32);
        perCallSleepMs = 250;
        TimeBudgetContext.set(new TimeBudget(150));

        List<Content> result = retriever.retrieve(QueryUtils.buildQuery("user question"));

        assertTrue(result.isEmpty());
        // The running task finishes ~250ms and queued tasks then hit their
        // pre-start budget check; give workers a bounded window to drain.
        Thread.sleep(600);
        assertEquals(1, calls.size(),
                "only the already-running task may touch the provider; queued tasks must skip");
    }

    // ------------------------------------------------------------------
    // 7. Preplanned (Conversate cue) path: single query stays a single call
    //    regardless of fanout; planning is still skipped.
    // ------------------------------------------------------------------
    @Test
    void preplannedQueryStillExecutesAsSingleCall() {
        var retriever = retriever();
        perCallSleepMs = 10;
        var query = QueryUtils.buildQuery("raw cue query",
                Map.of("webQueryAlreadyPlanned", true, "webTopK", 3));

        List<Content> result = retriever.retrieve(query);

        assertEquals(1, result.size());
        verify(provider, times(1)).search(anyString(), eq(3));
        verifyNoInteractions(planner);
    }
}
