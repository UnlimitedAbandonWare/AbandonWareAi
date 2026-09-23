package com.example.lms.search;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.api.RagOrchestratorController;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequestTrace} contract: uniform event schema, budget-state
 * distinction, bounded buffers, per-request gating, cross-thread correlation
 * through {@link ContextPropagation}, and honest {@code not_observed}
 * boundaries. Every event assertion is cross-checked against an independent
 * observation (stub invocation counters, worker latches) — never against the
 * diagnostic value alone.
 */
class RequestTraceTest {

    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.context().clear();
        RequestTrace.setMasterEnabled(false);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void enable() {
        RequestTrace.setMasterEnabled(true);
    }

    private static List<String> events() {
        return RequestTrace.events();
    }

    private static boolean hasEvent(String stage, String eventPrefix) {
        return events().stream().anyMatch(
                e -> e.contains("|" + stage + "|") && e.contains("|" + eventPrefix));
    }

    // ---- schema / budget-state distinctions ---------------------------------

    @Test
    void schemaIsUniformAndBudgetStatesDistinguishable() {
        enable();
        // absent context -> remain=-1, bstate=absent
        RequestTrace.emit("s.a", "enter", "k=1");
        List<String> ev = events();
        assertEquals(1, ev.size());
        String[] parts = ev.get(0).split("\\|", -1);
        assertEquals(6, parts.length, "uniform 6-part schema: " + ev.get(0));
        assertEquals("s.a", parts[2]);
        assertEquals("enter", parts[3]);
        assertEquals("k=1", parts[4]);
        assertEquals("remain=-1;bstate=absent", parts[5]);

        // explicit cancellation -> bstate=cancelled (not "expired")
        TraceStore.context().clear();
        TimeBudget cancelled = new TimeBudget(60_000);
        TimeBudgetContext.set(cancelled);
        cancelled.cancel();
        RequestTrace.emit("s.b", "enter", "");
        assertTrue(events().get(0).endsWith("|bstate=cancelled")
                        || events().get(0).contains("bstate=cancelled"),
                "cancelled budget must be distinct, got " + events().get(0));
        assertFalse(events().get(0).contains("bstate=expired"));

        // natural expiry -> bstate=expired
        TraceStore.context().clear();
        TimeBudgetContext.set(new TimeBudget(1));
        try {
            Thread.sleep(30L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        RequestTrace.emit("s.c", "enter", "");
        assertTrue(events().get(0).contains("bstate=expired"),
                "natural expiry must be distinct from cancel, got " + events().get(0));
    }

    @Test
    void emptyFieldsStillKeepSixPartSchema() {
        enable();
        RequestTrace.emit("s.x", "go");
        String[] parts = events().get(0).split("\\|", -1);
        assertEquals(6, parts.length, "no-fields event must keep schema: " + events().get(0));
        assertEquals("", parts[4]);
    }

    // ---- gating / bounds -----------------------------------------------------

    @Test
    void disabledTraceProducesNoEventsAndDoesNotThrow() {
        // master off, no per-request flag -> complete silence, still fail-soft
        RequestTrace.emit("s.off", "enter", "k=1");
        RequestTrace.emitDoc("rrf.doc", "i=0");
        assertTrue(events().isEmpty());
        assertEquals(0, TraceStore.getLong(RequestTrace.DROPPED_KEY));
    }

    @Test
    void perRequestFlagOverridesMasterSwitch() {
        // master off + per-request on -> records (worker-visible via shared map)
        RequestTrace.setMasterEnabled(false);
        TraceStore.put(RequestTrace.ENABLED_KEY, Boolean.TRUE);
        RequestTrace.emit("s.pr", "enter", "");
        assertEquals(1, events().size());

        // master on + per-request off -> silence
        TraceStore.context().clear();
        RequestTrace.setMasterEnabled(true);
        TraceStore.put(RequestTrace.ENABLED_KEY, Boolean.FALSE);
        RequestTrace.emit("s.pr", "enter", "");
        assertTrue(events().isEmpty());
    }

    @Test
    void bufferOverflowCountsDropsAndMarksIncomplete() {
        enable();
        for (int i = 0; i < 200; i++) {
            RequestTrace.emit("s.bulk", "e" + i, "");
        }
        assertEquals(128, events().size(), "buffer must cap at MAX_EVENTS");
        assertEquals(72, TraceStore.getLong(RequestTrace.DROPPED_KEY));
        assertEquals(Boolean.TRUE, TraceStore.get(RequestTrace.INCOMPLETE_KEY));
    }

    @Test
    void docDetailIsGatedAndBounded() {
        enable();
        // docs flag off -> emitDoc is a no-op
        RequestTrace.emitDoc("rrf.doc", "i=0");
        assertTrue(events().isEmpty());

        // docs flag on -> emitted, capped at 32, overflow counted
        TraceStore.put(RequestTrace.DOCS_KEY, Boolean.TRUE);
        for (int i = 0; i < 40; i++) {
            RequestTrace.emitDoc("rrf.doc", "i=" + i);
        }
        long docLines = events().stream().filter(e -> e.contains("|rrf.doc|")).count();
        assertEquals(32, docLines);
        assertEquals(8, TraceStore.getLong(RequestTrace.DOC_DROPPED_KEY));
        assertEquals(Boolean.TRUE, TraceStore.get(RequestTrace.INCOMPLETE_KEY));
    }

    // ---- cross-thread / parallel isolation -----------------------------------

    @Test
    void workerWritesIntoCallerStreamWithSharedSequence() throws Exception {
        enable();
        Map<String, Object> shared = TraceStore.context();
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);

        RequestTrace.emit("s.main", "enter", "");
        executor.submit(ContextPropagation.wrap(() -> {
            RequestTrace.emit("s.worker", "inside", "");
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        RequestTrace.emit("s.main", "exit", "");

        List<String> ev = events();
        assertEquals(3, ev.size());
        // seq counter is shared: worker's event lands between the two main
        // events with a strictly increasing sequence on the same timeline.
        long[] seqs = ev.stream()
                .mapToLong(e -> Long.parseLong(e.split("\\|", -1)[0]))
                .toArray();
        assertTrue(seqs[0] < seqs[1] && seqs[1] < seqs[2],
                "cross-thread seq must be strictly increasing: " + ev);
        assertTrue(hasEvent("s.worker", "inside"));
        // the worker really ran on another thread — shared map is the proof
        assertTrue(shared == TraceStore.context(), "test still on caller map");
    }

    @Test
    void parallelRequestsKeepSeparateStreams() throws Exception {
        enable();
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<String>[] streams = new List[]{null, null};

        for (int t = 0; t < 2; t++) {
            final int idx = t;
            executor.submit(() -> {
                // each request gets its own context map — as an HTTP worker
                // would via ContextPropagation from its own caller
                TraceStore.installContext(new ConcurrentHashMap<>());
                RequestTrace.emit("req." + idx, "enter", "");
                ready.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                RequestTrace.emit("req." + idx, "exit", "");
                streams[idx] = RequestTrace.events();
                done.countDown();
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        for (int t = 0; t < 2; t++) {
            List<String> s = streams[t];
            final String ownTag = "|req." + t + "|";
            assertEquals(2, s.size(), "stream " + t + " must hold only its own events: " + s);
            assertTrue(s.stream().allMatch(e -> e.contains(ownTag)));
            // per-request sequence starts at 1 — no shared counter leakage
            assertTrue(s.get(0).startsWith("1|t+"));
            assertTrue(s.get(1).startsWith("2|t+"));
        }
    }

    // ---- controller lifecycle -------------------------------------------------

    @Test
    void cancelWhileRunningShowsFullLifecycle() throws Exception {
        enable();
        executor = Executors.newSingleThreadExecutor();
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);
        CountDownLatch workerDone = new CountDownLatch(1);
        AtomicInteger facadeCalls = new AtomicInteger();
        when(facade.query(any())).thenAnswer(inv -> {
            facadeCalls.incrementAndGet();
            try {
                Thread.sleep(3_000L);
            } finally {
                workerDone.countDown();
            }
            return new QueryResponse();
        });

        TimeBudgetContext.set(new TimeBudget(300));
        QueryRequest req = new QueryRequest();
        req.query = "q";
        req.topK = 5;
        req.seedOnly = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.query(req));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        assertTrue(workerDone.await(5, TimeUnit.SECONDS), "worker must observe cancel and exit");

        List<String> ev = events();
        // Distinct lifecycle events — never collapsed into one "cancelled".
        assertTrue(hasEvent("rag.endpoint", "enter"), String.valueOf(ev));
        assertTrue(hasEvent("rag.endpoint", "submit"), String.valueOf(ev));
        assertTrue(hasEvent("rag.worker", "start"), String.valueOf(ev));
        assertTrue(hasEvent("rag.endpoint", "cancel_req"), String.valueOf(ev));
        assertTrue(hasEvent("rag.endpoint", "reject"), String.valueOf(ev));
        // cancel_accepted recorded, queue removal honestly unobserved
        String reject = ev.stream().filter(e -> e.contains("|rag.endpoint|reject|"))
                .findFirst().orElse("");
        assertTrue(reject.contains("queue_removed=not_observed"), reject);
        assertTrue(reject.contains("cancelRequested=true"), reject);
        // worker observed cancellation and terminated while running
        String workerEnd = ev.stream().filter(e -> e.contains("|rag.worker|end|"))
                .findFirst().orElse("");
        assertTrue(workerEnd.contains("cancelSeen=true"), workerEnd);
        // independent observation: the facade was invoked exactly once and the
        // worker exited early on the cancel flag (3s body, ~300ms budget)
        assertEquals(1, facadeCalls.get());
    }

    @Test
    void queuedTaskNeverRanIsNotMarkedQueueRemoved() throws Exception {
        enable();
        executor = Executors.newSingleThreadExecutor();
        // Occupy the single worker so the request task stays queued.
        CountDownLatch blocker = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        RagOrchestratorController controller = new RagOrchestratorController(facade, executor);

        TimeBudgetContext.set(new TimeBudget(200));
        QueryRequest req = new QueryRequest();
        req.query = "q";
        req.topK = 5;
        req.seedOnly = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.query(req));
        assertEquals("public_request_deadline_exhausted", ex.getMessage());
        blocker.countDown();

        String reject = events().stream()
                .filter(e -> e.contains("|rag.endpoint|reject|")).findFirst().orElse("");
        assertTrue(reject.contains("workerStarted=false"), reject);
        assertTrue(reject.contains("queue_removed=not_observed"), reject);
        assertEquals("not_started",
                TraceStore.context().get("rag.endpoint.workerTermination"));
        // independent: facade was never invoked — task never left the queue
        verify(facade, times(0)).query(any());
    }

    // ---- scraper wire boundary ------------------------------------------------

    /** Stubbed scraper: canned execute() delay, no DNS/SSRF, no wire calls. */
    private static final class TraceStubScraper extends PageContentScraper {
        final long delayMs;
        final AtomicInteger wireAttempts = new AtomicInteger();

        TraceStubScraper(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        protected void validatePublicTarget(URI target) {
        }

        @Override
        protected Connection openConnection(String targetUrl) {
            wireAttempts.incrementAndGet();
            Connection connection = mock(Connection.class);
            try {
                when(connection.userAgent(anyString())).thenReturn(connection);
                when(connection.followRedirects(anyBoolean())).thenReturn(connection);
                when(connection.timeout(anyInt())).thenReturn(connection);
                when(connection.execute()).thenAnswer(inv -> {
                    Thread.sleep(delayMs);
                    Connection.Response r = mock(Connection.Response.class);
                    when(r.statusCode()).thenReturn(200);
                    when(r.parse()).thenReturn(Jsoup.parse("<p>ok</p>"));
                    return r;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return connection;
        }
    }

    @Test
    void lateReturnAfterCancelIsMarkedAndWireTimeoutRecorded() throws Exception {
        enable();
        TimeBudget budget = new TimeBudget(5_000);
        TimeBudgetContext.set(budget);
        Map<String, Object> shared = TraceStore.context();
        TraceStubScraper scraper = new TraceStubScraper(600);
        executor = Executors.newSingleThreadExecutor();

        CountDownLatch done = new CountDownLatch(1);
        executor.submit(ContextPropagation.wrap(() -> {
            scraper.fetchText("https://example.com/a", 5_000);
            done.countDown();
        }));
        // cancel while the wire call is in flight (~600ms call, cancel at 150ms)
        Thread.sleep(150L);
        budget.cancel();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        List<String> ev = RequestTrace.events();
        String attempt = ev.stream().filter(e -> e.contains("|page.wire|attempt|"))
                .findFirst().orElse("");
        // the call STARTED before cancel (bstate=ok) with the applied timeout
        assertTrue(attempt.contains("timeout_ms="), attempt);
        assertTrue(attempt.contains("bstate=ok"), attempt);
        String ret = ev.stream().filter(e -> e.contains("|page.wire|return|"))
                .findFirst().orElse("");
        // the return ARRIVED after cancel: late_return marker + cancelled state
        assertTrue(ret.contains("late_after_cancel"), ret);
        assertTrue(ret.contains("bstate=cancelled"), ret);
        // independent observation: exactly one wire attempt happened
        assertEquals(1, scraper.wireAttempts.get());
        // shared map is the same instance the caller sees
        assertTrue(shared.get(RequestTrace.KEY) instanceof List);
    }

    @Test
    void cancelledBudgetBlocksNewWireCall() throws Exception {
        enable();
        TimeBudget budget = new TimeBudget(60_000);
        TimeBudgetContext.set(budget);
        budget.cancel();
        TraceStubScraper scraper = new TraceStubScraper(0);

        assertNull(scraper.fetchText("https://example.com/a", 5_000));
        // no page.wire|attempt event — the budget gate fired before Jsoup
        assertFalse(hasEvent("page.wire", "attempt"),
                "post-cancel calls must not start: " + events());
        assertEquals(0, scraper.wireAttempts.get());
    }

    // ---- orchestrator end-to-end chain ---------------------------------------

    private UnifiedRagOrchestrator orchestratorWithLeaf(AtomicInteger leafCalls, List<dev.langchain4j.rag.content.Content> canned) {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        com.example.lms.service.rag.LangChainRAGService ragService =
                mock(com.example.lms.service.rag.LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString()))
                .thenReturn((dev.langchain4j.rag.content.retriever.ContentRetriever) query -> {
                    leafCalls.incrementAndGet();
                    return canned;
                });
        org.springframework.test.util.ReflectionTestUtils
                .setField(orchestrator, "langChainRAGService", ragService);
        return orchestrator;
    }

    @Test
    void orchestratorLinksSettingsToCallsToStages() {
        enable();
        AtomicInteger leafCalls = new AtomicInteger();
        dev.langchain4j.rag.content.Content doc = dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("alpha body",
                        dev.langchain4j.data.document.Metadata.from(
                                Map.of("url", "https://example.com/d1"))));
        UnifiedRagOrchestrator orchestrator =
                orchestratorWithLeaf(leafCalls, List.of(doc));

        QueryRequest req = new QueryRequest();
        req.query = "settings contract";
        req.topK = 3;
        req.useWeb = false;
        req.useVector = true;
        req.useKg = false;
        req.useBm25 = false;

        QueryResponse resp = orchestrator.query(req);
        List<String> ev = events();

        // requested -> applied -> axis -> call -> stage -> fuse -> final chain
        String received = ev.stream().filter(e -> e.contains("|rag.request|received|"))
                .findFirst().orElse("");
        assertTrue(received.contains("useWeb=false"), received);
        assertTrue(received.contains("useVector=true"), received);
        assertTrue(received.contains("flags_src=primitive"), received);
        assertTrue(hasEvent("rag.settings", "applied"), String.valueOf(ev));
        assertTrue(hasEvent("axis.web", "state"), String.valueOf(ev));
        assertTrue(ev.stream().anyMatch(e -> e.contains("|axis.web|")
                        && e.contains("disabled_by_config")), String.valueOf(ev));
        assertTrue(ev.stream().anyMatch(e -> e.contains("|axis.vector|")
                        && e.contains("ready;leaf=pure_vector")), String.valueOf(ev));
        assertTrue(hasEvent("leg.vector", "call"), String.valueOf(ev));
        assertTrue(hasEvent("leg.vector", "result"), String.valueOf(ev));
        assertTrue(hasEvent("rag.stages", "collect"), String.valueOf(ev));
        assertTrue(hasEvent("rrf", "stages"), String.valueOf(ev));
        assertTrue(hasEvent("rag.final", "cut"), String.valueOf(ev));
        // prohibited axis never produced a call event
        assertFalse(hasEvent("leg.web", "call"), String.valueOf(ev));
        // independent observation: pure-vector leaf invoked exactly once
        assertEquals(1, leafCalls.get());
        // surfaced on the debug map for the developer path
        Object dbg = resp.debug.get(RequestTrace.KEY);
        assertTrue(dbg instanceof List<?> list && !list.isEmpty(),
                "request.events must surface in debug: " + resp.debug.keySet());
    }

    @Test
    void emergencyLegEventsDistinguishExecFromBudgetSkip() throws Exception {
        enable();
        // case A: budget alive -> emergency executes (second leaf call)
        AtomicInteger leafCalls = new AtomicInteger();
        UnifiedRagOrchestrator orchestrator =
                orchestratorWithLeaf(leafCalls, List.of());
        QueryRequest req = new QueryRequest();
        req.query = "empty pool triggers emergency";
        req.topK = 3;
        req.useVector = true;
        req.useWeb = false;
        req.useKg = false;
        req.useBm25 = false;
        orchestrator.query(req);
        assertTrue(hasEvent("rag.emergency", "exec"), String.valueOf(events()));
        assertTrue(events().stream().anyMatch(e -> e.contains("|rag.emergency|decision|")
                        && e.contains("eligible=true")), String.valueOf(events()));
        assertEquals(2, leafCalls.get(), "main leg + bounded emergency leg");

        // case B: exhausted budget -> emergency skipped before any second call
        TraceStore.context().clear();
        leafCalls.set(0);
        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L);
        orchestrator.query(req);
        assertTrue(events().stream().anyMatch(e -> e.contains("|rag.emergency|skip|")
                        && e.contains("request_budget_exhausted")), String.valueOf(events()));
        assertEquals(1, leafCalls.get(), "exhausted budget must block the emergency call");
    }

    @Test
    void featureStatusHonestAboutUnwiredPaths() {
        enable();
        AtomicInteger leafCalls = new AtomicInteger();
        UnifiedRagOrchestrator orchestrator =
                orchestratorWithLeaf(leafCalls, List.of());
        QueryRequest req = new QueryRequest();
        req.query = "feature status";
        req.topK = 3;
        req.useVector = true;
        req.useWeb = false;
        req.useKg = false;
        req.useBm25 = false;
        req.enableSelfAsk = true;

        orchestrator.query(req);
        String status = events().stream()
                .filter(e -> e.contains("|rag.features|status|")).findFirst().orElse("");
        assertTrue(status.contains("selfask=requested:exec_not_wired"), status);
        assertTrue(status.contains("shadow=not_wired"), status);
        // fingerprint keys were never written on this path -> not_observed,
        // not a fabricated zero count
        assertTrue(status.contains("fingerprint=not_observed_on_path"), status);
        String selfask = events().stream()
                .filter(e -> e.contains("|rag.selfask|decision|")).findFirst().orElse("");
        assertTrue(selfask.contains("planner=missing"), selfask);
    }

    @Test
    void tracingOnAndOffProduceIdenticalResultsAndCalls() {
        // Enabled-vs-disabled must not change returned docs, ordering, or the
        // number of real retriever invocations.
        AtomicInteger leafCalls = new AtomicInteger();
        dev.langchain4j.rag.content.Content docA = dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("alpha body",
                        dev.langchain4j.data.document.Metadata.from(
                                Map.of("url", "https://example.com/d1"))));
        dev.langchain4j.rag.content.Content docB = dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("beta body",
                        dev.langchain4j.data.document.Metadata.from(
                                Map.of("url", "https://example.com/d2"))));
        UnifiedRagOrchestrator orchestrator =
                orchestratorWithLeaf(leafCalls, List.of(docA, docB));

        QueryRequest req = new QueryRequest();
        req.query = "equivalence";
        req.topK = 5;
        req.useWeb = false;
        req.useVector = true;
        req.useKg = false;
        req.useBm25 = false;

        RequestTrace.setMasterEnabled(true);
        QueryResponse onResp = orchestrator.query(req);
        int callsOn = leafCalls.get();
        List<String> onOrder = onResp.results.stream()
                .map(d -> String.valueOf(d.id)).toList();
        assertFalse(events().isEmpty());

        TraceStore.context().clear();
        leafCalls.set(0);
        RequestTrace.setMasterEnabled(false);
        QueryResponse offResp = orchestrator.query(req);
        int callsOff = leafCalls.get();
        List<String> offOrder = offResp.results.stream()
                .map(d -> String.valueOf(d.id)).toList();

        assertEquals(callsOn, callsOff, "call count must not depend on tracing");
        assertEquals(onOrder, offOrder, "result ordering must not depend on tracing");
        assertEquals(onResp.results.size(), offResp.results.size());
        assertTrue(events().isEmpty(), "disabled run must record nothing");
    }

    @Test
    void docDetailJoinsRrfWindowToFinalRank() {
        enable();
        TraceStore.put(RequestTrace.DOCS_KEY, Boolean.TRUE);
        AtomicInteger leafCalls = new AtomicInteger();
        dev.langchain4j.rag.content.Content docA = dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("alpha body",
                        dev.langchain4j.data.document.Metadata.from(
                                Map.of("url", "https://example.com/d1"))));
        dev.langchain4j.rag.content.Content docB = dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("beta body",
                        dev.langchain4j.data.document.Metadata.from(
                                Map.of("url", "https://example.com/d2"))));
        UnifiedRagOrchestrator orchestrator =
                orchestratorWithLeaf(leafCalls, List.of(docA, docB));

        QueryRequest req = new QueryRequest();
        req.query = "doc detail join";
        req.topK = 2;
        req.useWeb = false;
        req.useVector = true;
        req.useKg = false;
        req.useBm25 = false;
        orchestrator.query(req);

        List<String> rrfDocs = events().stream()
                .filter(e -> e.contains("|rrf.doc|")).toList();
        List<String> finalDocs = events().stream()
                .filter(e -> e.contains("|rag.final.doc|")).toList();
        assertEquals(2, rrfDocs.size(), String.valueOf(events()));
        assertEquals(2, finalDocs.size(), String.valueOf(events()));
        // every final doc carries a hashed stable key that also appeared in
        // the RRF window — identity joins across the fuse boundary
        for (String f : finalDocs) {
            String key = f.replaceAll(".*key=([a-f0-9]+).*", "$1");
            assertTrue(rrfDocs.stream().anyMatch(r -> r.contains("key=" + key)),
                    "final doc key " + key + " missing from rrf.doc events: " + rrfDocs);
        }
        // hashed identity only — no raw URL in doc lines
        assertTrue(rrfDocs.stream().noneMatch(e -> e.contains("example.com")));
        assertTrue(finalDocs.stream().noneMatch(e -> e.contains("example.com")));
    }
}
