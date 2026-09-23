package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangChainRAGServiceVectorBudgetTest {
    record Outcome(List<Content> contents, Map<String, Object> trace, boolean interrupted) {}

    @ParameterizedTest(name = "vector budget {0}/{1}")
    @CsvSource({"embedding,short", "search,short", "scope_relax,short", "embedding,long", "search,long", "scope_relax,long",
            "search,authored", "search,removed", "search,specific"})
    void planBudgetBoundsCallerWaitWithoutClaimingProviderTermination(String stage, String control) throws Exception {
        try (Fixture f = new Fixture(stage, 1)) {
            boolean bounded = control.equals("short") || control.equals("specific");
            Future<Outcome> caller = f.call(planQuery(control), null);
            assertTrue(f.entered.await(2, TimeUnit.SECONDS), "controlled provider call entered");
            if (bounded) {
                Outcome out = caller.get(1, TimeUnit.SECONDS);
                assertTrue(out.contents().isEmpty());
                assertEquals("deadline_exhausted", out.trace().get("vector.budget.reason"));
                assertEquals(Boolean.FALSE, out.trace().get("vector.budget.workerFinishedAtReturn"));
                assertEquals(1, f.inFlight.get(), "caller timeout does not terminate backing provider call");
                assertEquals(0, f.interruptions.get(), "timeout requests cancel(false)");
            } else {
                assertThrows(TimeoutException.class, () -> caller.get(350, TimeUnit.MILLISECONDS));
                f.release.countDown();
                assertEquals(1, caller.get(1, TimeUnit.SECONDS).contents().size());
            }
            f.release.countDown();
            assertTrue(f.finished.await(1, TimeUnit.SECONDS));
            if (bounded && stage.equals("embedding")) assertEquals(0, f.searchCalls.get(), "no store call after embedding outlives budget");
            if (stage.equals("scope_relax")) assertEquals(2, f.searchCalls.get(), "no new doc relaxation after expiry");
            System.out.printf("TBL07_VECTOR_BUDGET stage=%s control=%s callerBounded=%s providerWire=not_observed%n", stage, control, bounded);
        }
    }

    @Test
    void outerRequestDeadlineCapsLongVectorPlan() throws Exception {
        try (Fixture f = new Fixture("embedding", 1)) {
            Future<Outcome> caller = f.call(planQuery("long"), new TimeBudget(250));
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            assertTrue(caller.get(1, TimeUnit.SECONDS).contents().isEmpty());
            assertEquals(1, f.inFlight.get());
            f.release.countDown(); assertTrue(f.finished.await(1, TimeUnit.SECONDS));
            assertEquals(0, f.searchCalls.get());
        }
    }

    @Test
    void expiredRequestStartsNoEmbeddingOrStoreCall() throws Exception {
        try (Fixture f = new Fixture("none", 0)) {
            Outcome out = f.call(planQuery("authored"), TimeBudget.untilNanoDeadline(System.nanoTime() - 1)).get(1, TimeUnit.SECONDS);
            assertTrue(out.contents().isEmpty());
            assertEquals(0, f.embeddingCalls.get()); assertEquals(0, f.searchCalls.get());
            assertEquals("deadline_exhausted", out.trace().get("vector.budget.reason"));
        }
    }

    @Test
    void saturationRejectsNewWorkWhileTimedOutProvidersRemainActive() throws Exception {
        try (Fixture f = new Fixture("embedding", 4)) {
            List<Future<Outcome>> first = new ArrayList<>();
            for (int i = 0; i < 4; i++) first.add(f.call(planQuery("short"), null));
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            for (Future<Outcome> caller : first) assertTrue(caller.get(1, TimeUnit.SECONDS).contents().isEmpty());
            Outcome fifth = f.call(planQuery("short"), null).get(500, TimeUnit.MILLISECONDS);
            assertEquals("executor_saturated", fifth.trace().get("vector.budget.reason"));
            assertTrue(fifth.contents().isEmpty()); assertEquals(4, f.embeddingCalls.get());
            assertEquals(4, f.inFlight.get()); assertEquals(0, f.interruptions.get());
            f.release.countDown(); assertTrue(f.finished.await(1, TimeUnit.SECONDS));
            assertEquals(0, f.searchCalls.get());
        }
    }

    @Test
    void callerInterruptDoesNotInterruptProviderOrStartFollowupSearch() throws Exception {
        try (Fixture f = new Fixture("embedding", 1)) {
            Future<Outcome> caller = f.call(planQuery("long"), null);
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            f.callerThread.get().interrupt();
            Outcome out = caller.get(1, TimeUnit.SECONDS);
            assertTrue(out.contents().isEmpty()); assertTrue(out.interrupted());
            assertEquals("caller_interrupted", out.trace().get("vector.budget.reason"));
            assertEquals(0, f.interruptions.get()); assertEquals(1, f.inFlight.get());
            f.release.countDown(); assertTrue(f.finished.await(1, TimeUnit.SECONDS)); assertEquals(0, f.searchCalls.get());
        }
    }

    @Test
    void scopeRetryUsesRemainingDeadlineInsteadOfReceivingNewBudget() throws Exception {
        try (Fixture f = new Fixture("shared", 1)) {
            Future<Outcome> caller = f.call(QueryUtils.buildQuery("synthetic shared deadline", Map.of("vecBudgetMs", 1000L, "scope_anchor_key", "alpha")), null);
            assertTrue(f.firstSearch.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> caller.get(650, TimeUnit.MILLISECONDS));
            f.releaseFirst.countDown();
            assertTrue(f.entered.await(500, TimeUnit.MILLISECONDS));
            Outcome out = caller.get(700, TimeUnit.MILLISECONDS);
            assertTrue(out.contents().isEmpty()); assertEquals(1, f.inFlight.get());
            f.release.countDown(); assertTrue(f.finished.await(1, TimeUnit.SECONDS)); assertEquals(2, f.searchCalls.get());
        }
    }

    @ParameterizedTest(name = "raw vector budget {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "string", "zero", "negative", "fractional", "nan", "infinity", "boolean", "invalid", "large"})
    void rawBudgetBoundaryNeverTurnsInvalidValuesIntoUnlimitedCalls(String control) throws Exception {
        try (Fixture f = new Fixture("none", 0)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            Object value = switch (control) {
                case "missing" -> null; case "string" -> "2000"; case "zero" -> 0; case "negative" -> -1;
                case "fractional" -> 0.5d; case "nan" -> Double.NaN; case "infinity" -> Double.POSITIVE_INFINITY;
                case "boolean" -> true; case "large" -> Long.MAX_VALUE; default -> "synthetic_invalid_budget";
            };
            if (value != null) meta.put("vecBudgetMs", value);
            Outcome out = f.call(QueryUtils.buildQuery("synthetic raw budget", meta), null).get(1, TimeUnit.SECONDS);
            boolean accepted = List.of("missing", "string", "large").contains(control);
            assertEquals(accepted ? 1 : 0, out.contents().size());
            assertEquals(accepted ? 1 : 0, f.embeddingCalls.get()); assertEquals(accepted ? 1 : 0, f.searchCalls.get());
            if (!accepted) assertEquals(control.equals("zero") ? "deadline_exhausted" : "invalid_budget", out.trace().get("vector.budget.reason"));
            if (control.equals("missing")) assertSame(f.callerThread.get(), f.providerThread.get(), "legacy no-budget call remains synchronous");
            if (control.equals("large")) {
                var parser = LangChainRAGService.class.getDeclaredMethod("vectorBudgetMillis", Object.class); parser.setAccessible(true);
                assertEquals(120000L, parser.invoke(null, value));
            }
            assertFalse(String.valueOf(out.trace()).contains("synthetic_invalid_budget"));
        }
    }

    @Test
    void providerReceivesCallerContextAndCallerKeepsOriginalRequestBudget() throws Exception {
        try (Fixture f = new Fixture("none", 0)) {
            f.attachContext = true; TimeBudget outer = new TimeBudget(2000);
            Outcome out = f.call(planQuery("long"), outer).get(1, TimeUnit.SECONDS);
            assertEquals(1, out.contents().size()); assertSame(f.expectedGuard, f.seenGuard.get());
            assertEquals("synthetic-context", f.seenMdc.get()); assertSame(f.callerTrace.get(), f.seenTrace.get());
            assertEquals(Boolean.TRUE, out.trace().get("fixture.safeTrace"));
            assertNotSame(f.callerThread.get(), f.providerThread.get());
            assertNotNull(f.seenBudget.get()); assertNotSame(outer, f.seenBudget.get());
            assertTrue(f.seenBudget.get().remainingMillis() <= 2000); assertSame(outer, f.restoredCallerBudget.get());
            assertEquals(Boolean.TRUE, out.trace().get("vector.budget.workerFinishedAtReturn"));
        }
    }

    @Test
    void closedServiceRejectsNewBudgetedWorkWithoutCallingProvider() throws Exception {
        try (Fixture f = new Fixture("none", 0)) {
            assertEquals(1, f.call(planQuery("long"), null).get(1, TimeUnit.SECONDS).contents().size());
            var close = LangChainRAGService.class.getDeclaredMethod("shutdownVectorBudgetExecutor"); close.setAccessible(true); close.invoke(f.service);
            Outcome after = f.call(planQuery("long"), null).get(1, TimeUnit.SECONDS);
            assertTrue(after.contents().isEmpty()); assertEquals("executor_shutdown", after.trace().get("vector.budget.reason"));
            assertEquals(1, f.embeddingCalls.get()); assertEquals(1, f.searchCalls.get());
        }
    }

    @ParameterizedTest(name = "budgeted provider failure {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"embedding_failure", "store_failure"})
    void budgetedProviderFailureRetainsFailSoftDiagnostics(String stage) throws Exception {
        try (Fixture f = new Fixture(stage, 0)) {
            Outcome out = f.call(planQuery("long"), null).get(1, TimeUnit.SECONDS);
            assertTrue(out.contents().isEmpty()); assertEquals("exception", out.trace().get("vector.retrieval.emptyReason"));
            assertEquals("IllegalStateException", out.trace().get("vector.retrieval.failureClass"));
            assertEquals(Boolean.TRUE, out.trace().get("vector.budget.workerFinishedAtReturn"));
            assertFalse(String.valueOf(out.trace()).contains("synthetic_provider_failure"));
            assertEquals(stage.equals("embedding_failure") ? 0 : 1, f.searchCalls.get());
        }
    }

    static Query planQuery(String control) throws Exception {
        String id = "kg_first.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/" + id + ".yaml")));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var budgets = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("budgets");
        if (control.equals("short")) budgets.put("total_ms", 250);
        if (control.equals("long")) budgets.put("total_ms", 2500);
        if (control.equals("removed")) budgets.remove("total_ms");
        if (control.equals("specific")) budgets.put("vec_ms", 250);
        var restored = changed.deepCopy(); restored.set("budgets", original.path("budgets")); assertEquals(original, restored);
        assertEquals(1200, budgets.path("web_ms").asInt());
        byte[] bytes = mapper.writeValueAsBytes(changed);
        var resolver = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (location.equals("classpath:plans/" + id + ".yaml")) return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return id + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(control.equals("authored") ? new org.springframework.core.io.DefaultResourceLoader() : resolver);
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> meta = new LinkedHashMap<>(); applier.applyToHintsAndMeta(applier.load(id), hints, meta);
        long expected = switch (control) { case "short", "specific" -> 250L; case "long" -> 2500L; case "removed" -> 3000L; default -> 3500L; };
        assertEquals(expected, hints.getVecBudgetMs()); assertEquals(expected, meta.get("vecBudgetMs"));
        meta.put("scope_anchor_key", "alpha");
        return QueryUtils.buildQuery("synthetic vector budget", meta);
    }

    static final class Fixture implements AutoCloseable {
        final CountDownLatch entered, finished, release = new CountDownLatch(1), firstSearch = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        final AtomicInteger inFlight = new AtomicInteger(), embeddingCalls = new AtomicInteger(), searchCalls = new AtomicInteger(), interruptions = new AtomicInteger();
        final AtomicReference<Thread> callerThread = new AtomicReference<>();
        final AtomicReference<Thread> providerThread = new AtomicReference<>();
        final AtomicReference<TimeBudget> seenBudget = new AtomicReference<>(), restoredCallerBudget = new AtomicReference<>();
        final AtomicReference<GuardContext> seenGuard = new AtomicReference<>();
        final AtomicReference<String> seenMdc = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> callerTrace = new AtomicReference<>(), seenTrace = new AtomicReference<>();
        final GuardContext expectedGuard = GuardContext.defaultContext();
        boolean attachContext;
        final ExecutorService callers = Executors.newFixedThreadPool(6);
        final LangChainRAGService service;
        Fixture(String stage, int blocks) {
            entered = new CountDownLatch(blocks); finished = new CountDownLatch(blocks);
            var embedding = Embedding.from(new float[] {1.0f});
            EmbeddingModel model = mock(EmbeddingModel.class);
            when(model.embed(anyString())).thenAnswer(invocation -> { embeddingCalls.incrementAndGet(); captureContext(); if (stage.equals("embedding_failure")) throw new IllegalStateException("synthetic_provider_failure"); if (stage.equals("embedding")) block(); return Response.from(embedding); });
            @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
            when(store.search(any(EmbeddingSearchRequest.class))).thenAnswer(invocation -> {
                int call = searchCalls.incrementAndGet();
                if (stage.equals("store_failure")) throw new IllegalStateException("synthetic_provider_failure");
                if (stage.equals("shared") && call == 1) { firstSearch.countDown(); awaitRelease(releaseFirst); }
                if ((stage.equals("scope_relax") || stage.equals("shared")) && call == 1) return new EmbeddingSearchResult<>(List.of());
                if (stage.equals("search") || ((stage.equals("scope_relax") || stage.equals("shared")) && call == 2)) block();
                return new EmbeddingSearchResult<>(List.of(new EmbeddingMatch<>(.9d, "synthetic-match", embedding, TextSegment.from("synthetic evidence"))));
            });
            service = new LangChainRAGService(model, store);
            if (stage.equals("scope_relax") || stage.equals("shared")) {
                org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeFilterEnabled", true);
                org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeFilterMinMatches", 1);
                org.springframework.test.util.ReflectionTestUtils.setField(service, "docTypeFilterEnabled", true);
                org.springframework.test.util.ReflectionTestUtils.setField(service, "docTypeFilterMinMatches", 1);
            }
        }
        Future<Outcome> call(Query q, TimeBudget outer) {
            return callers.submit(() -> {
                callerThread.set(Thread.currentThread()); TraceStore.clear(); TimeBudgetContext.clear();
                if (outer != null) TimeBudgetContext.set(outer);
                if (attachContext) { GuardContextHolder.set(expectedGuard); MDC.put("fixture", "synthetic-context"); TraceStore.put("fixture.safeTrace", true); }
                callerTrace.set(TraceStore.context());
                try {
                    List<Content> out = service.asContentRetriever("test-index").retrieve(q);
                    restoredCallerBudget.set(TimeBudgetContext.get());
                    return new Outcome(out, new LinkedHashMap<>(TraceStore.getAll()), Thread.currentThread().isInterrupted());
                }
                finally { Thread.interrupted(); TimeBudgetContext.clear(); TraceStore.clear(); GuardContextHolder.clear(); MDC.clear(); }
            });
        }
        void captureContext() {
            providerThread.set(Thread.currentThread()); seenBudget.set(TimeBudgetContext.get()); seenGuard.set(GuardContextHolder.get());
            seenMdc.set(MDC.get("fixture")); seenTrace.set(TraceStore.context());
        }
        void block() { inFlight.incrementAndGet(); entered.countDown(); try { awaitRelease(release); } finally { inFlight.decrementAndGet(); finished.countDown(); } }
        void awaitRelease(CountDownLatch latch) { boolean done = false; while (!done) { try { if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("fixture release deadline"); done = true; } catch (InterruptedException interrupted) { interruptions.incrementAndGet(); } } }
        @Override public void close() throws Exception {
            releaseFirst.countDown(); release.countDown(); callers.shutdown(); assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
            try { var close = LangChainRAGService.class.getDeclaredMethod("shutdownVectorBudgetExecutor"); close.setAccessible(true); close.invoke(service);
                var field = LangChainRAGService.class.getDeclaredField("vectorBudgetExecutor"); field.setAccessible(true);
                assertTrue(((ExecutorService) field.get(service)).awaitTermination(1, TimeUnit.SECONDS)); }
            catch (NoSuchMethodException baselineHasNoExecutor) { /* RED baseline is synchronous. */ }
            assertEquals(0, inFlight.get());
        }
    }
}
