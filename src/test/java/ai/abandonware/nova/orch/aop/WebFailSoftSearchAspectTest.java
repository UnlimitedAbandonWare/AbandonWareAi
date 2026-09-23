package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import ai.abandonware.nova.orch.probe.WebSoakKpiLastStore;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import ai.abandonware.nova.orch.web.WebSnippet;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebFailSoftSearchAspectTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void webFailSoftFailureTraceNormalizesNumericErrorType() {
        String rawQuery = "private customer query ownerToken=raw-secret";

        WebFailSoftFailureTrace.record(
                "web.failsoft.extraSearch",
                new NumberFormatException("ownerToken=raw-secret"),
                rawQuery);

        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.extraSearch.failed"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.extraSearch.failed.count"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.extraSearch.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.extraSearch.queryHash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("web.failsoft.extraSearch.queryLength"));
        assertNull(TraceStore.get("web.failsoft.extraSearch.query"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
        assertFalse(trace.contains("private customer query"), trace);
    }

    @Test
    void webFailSoftTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        String rawStage = "providerBackoff.braveResetTrace " + com.example.lms.test.SecretFixtures.openAiKey();

        WebFailSoftTraceSuppressions.trace(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("web.failsoft.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("web.failsoft.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("web.failsoft.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void intentionalCancelSummaryDoesNotOpenProviderBackoff() {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(backoff));
        TraceStore.put("web.await.events.summary.intentional_cancel.waitedMs0.engines", "Naver,Brave");

        ReflectionTestUtils.invokeMethod(aspect, "maybeApplyProviderBackoffFromAwaitSummary");

        assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER).shouldSkip());
        assertFalse(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE).shouldSkip());
        assertEquals("CANCELLED_NO_BREAKER",
                TraceStore.get("web.failsoft.rateLimitBackoff.naver.last.kind"));
        assertEquals("CANCELLED_NO_BREAKER",
                TraceStore.get("web.failsoft.rateLimitBackoff.brave.last.kind"));
        assertEquals("await_summary", TraceStore.get("web.failsoft.cancelled.naver.lastType"));
        assertEquals("await_summary", TraceStore.get("web.failsoft.cancelled.brave.lastType"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.cancelled.naver.count"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.cancelled.brave.count"));
        assertNull(TraceStore.get("web.naver.skipped"));
        assertNull(TraceStore.get("web.brave.skipped"));
    }

    @Test
    void awaitSummaryAppliesSerpApiAndTavilyProviderBackoff() {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(backoff));
        TraceStore.put("web.await.events.summary.engine.SerpApi.cause.await_timeout.count", 2L);
        TraceStore.put("web.await.events.summary.engine.Tavily.cause.await_timeout.count", 1L);

        ReflectionTestUtils.invokeMethod(aspect, "maybeApplyProviderBackoffFromAwaitSummary");

        assertTrue(backoff.shouldSkip("serpapi").shouldSkip());
        assertTrue(backoff.shouldSkip("tavily").shouldSkip());
        assertEquals(true, TraceStore.get("web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutApplied"));
        assertEquals(true, TraceStore.get("web.failsoft.rateLimitBackoff.tavily.awaitTimeoutApplied"));
        assertEquals(2L, TraceStore.get("web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutDetected"));
        assertEquals(1L, TraceStore.get("web.failsoft.rateLimitBackoff.tavily.awaitTimeoutDetected"));

        ReflectionTestUtils.invokeMethod(aspect, "refreshRateLimitBackoffKpis", 0);

        assertEquals(2L, TraceStore.get("web.failsoft.rateLimitBackoff.skipped.cooldown.count"));
        assertEquals(3L, TraceStore.get("web.failsoft.rateLimitBackoff.awaitTimeoutReconciledApplyTimes"));
        assertTrue(TraceStore.getLong("web.failsoft.rateLimitBackoff.max.remainingMs") > 0L);
    }

    @Test
    void initialHybridSearchRateLimitFailureReturnsEmptyWithRedactedSkipReason() throws Throwable {
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                new NovaWebFailSoftProperties(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "private customer query ownerToken=raw-secret";
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 3 });
        when(pjp.proceed(any(Object[].class))).thenThrow(WebClientResponseException.create(
                429,
                "Too Many Requests",
                null,
                "ownerToken=raw-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));

        Object result = assertDoesNotThrow(() -> aspect.aroundSearch(pjp));

        assertEquals(List.of(), result);
        assertEquals("rate_limited", TraceStore.get("web.failsoft.error"));
        assertEquals("rate_limited", TraceStore.get("web.hybrid.skipped.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.hybrid.rateLimited"));
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.error.queryHash")).startsWith("hash:"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("Too Many Requests"), trace);
    }

    @Test
    void initialHybridSearchCancellationFailureReturnsEmptyWithCancelledTrace() throws Throwable {
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                new NovaWebFailSoftProperties(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "private customer query ownerToken=raw-secret";
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 3 });
        when(pjp.proceed(any(Object[].class)))
                .thenThrow(new CancellationException("cancelled ownerToken=raw-secret"));

        Object result = assertDoesNotThrow(() -> aspect.aroundSearch(pjp));

        assertEquals(List.of(), result);
        assertEquals("cancelled", TraceStore.get("web.failsoft.error"));
        assertEquals("cancelled", TraceStore.get("web.hybrid.skipped.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.hybrid.cancelled"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("CancellationException"), trace);
    }

    @Test
    void boundedHybridRoutePreventsAllAspectOwnedExtraSearchCalls() throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(2);
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        AtomicInteger calls = new AtomicInteger();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "Gemini API pricing evidence", 3 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            TraceStore.put("web.boundedRoute", true);
            TraceStore.put("web.boundedRoute.completed", true);
            return List.of();
        });

        Object result = aspect.aroundSearch(pjp);

        assertEquals(List.of(), result);
        assertEquals(1, calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.extraCalls.skipped"));
        assertEquals("boundedRoute", TraceStore.get("web.failsoft.extraCalls.skipped.reason"));
    }

    @Test
    void conversateBoundedRouteKeepsDistinctDocumentsFromOneHost() throws Throwable {
        var docs = List.of("Python API reference https://docs.python.org/3/reference/a",
                "Python API constraints https://docs.python.org/3/reference/b",
                "Python API limitations https://docs.python.org/3/reference/c");
        assertEquals(3, boundedCueDocuments(true, false, docs).size());
    }

    @Test
    void ordinaryBoundedRouteStillRequiresHostDiversity() throws Throwable {
        var docs = List.of("Python API reference https://docs.python.org/3/reference/a",
                "Python API constraints https://docs.python.org/3/reference/b",
                "Python API limitations https://docs.python.org/3/reference/c");
        assertEquals(1, boundedCueDocuments(false, false, docs).size());
    }

    @Test
    void conversateOfficialOnlyPreservesExistingFallbackSelection() throws Throwable {
        var docs = List.of("Python API reference https://docs.python.org/3/reference/a",
                "Python API constraints https://docs.python.org/3/reference/b");
        assertEquals(2, boundedCueDocuments(true, true, docs).size());
    }

    @Test
    void conversateBoundedRouteStillDropsDuplicateUrlsAndTechnicalSpam() throws Throwable {
        String doc = "Python API reference https://docs.python.org/3/reference/a";
        var out = boundedCueDocuments(true, false, List.of(doc, doc,
                "Python API loan-spam https://docs.python.org/3/reference/spam"));
        assertEquals(1, out.size());
        assertFalse(out.get(0).contains("loan-spam"));
    }

    private List<String> boundedCueDocuments(boolean cue, boolean officialOnly, List<String> docs) throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(2);
        props.setTechSpamKeywords(List.of("loan-spam"));
        var aspect = new WebFailSoftSearchAspect(props, new RuleBasedQueryAugmenter(props),
                null, null, null, null, null, null, null, new FixedProvider<>(null));
        GuardContext context = new GuardContext();
        context.setMinCitations(3);
        context.setOfficialOnly(officialOnly);
        GuardContextHolder.set(context);
        TraceStore.put("conversate.web.singleCycle", cue);
        AtomicInteger calls = new AtomicInteger();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "OpenAI API pricing quota", 3 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            TraceStore.put("web.boundedRoute", true);
            return docs.stream().map(doc -> "[WEB:DOCS|CRED:TRUSTED] " + doc).toList();
        });
        try {
            @SuppressWarnings("unchecked")
            var out = (List<String>) aspect.aroundSearch(pjp);
            assertEquals(1, calls.get());
            return out;
        } finally {
            GuardContextHolder.clear();
        }
    }

    @Test
    void cheapSearchModeSkipsExtraOfficialDocsRescueCalls() throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(2);
        props.setOfficialDocsRescueQueries(List.of("{canonical} official docs"));
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(2);
        context.setCheapSearchMode(true);
        GuardContextHolder.set(context);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "qwen api pricing quota";
        AtomicInteger calls = new AtomicInteger();
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 4 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return List.of("[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Community note https://community.example/qwen");
            }
            return List.of("[WEB:DOCS|CRED:TRUSTED] Official docs https://docs.example/qwen");
        });

        try {
            Object result = assertDoesNotThrow(() -> aspect.aroundSearch(pjp));

            assertEquals(1, calls.get(), "FORCE_LIGHT/cheap mode should not issue rescue provider calls");
            assertTrue(result instanceof List<?>);
            assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.extraCalls.skipped"));
            assertEquals("cheapSearchMode", TraceStore.get("web.failsoft.extraCalls.skipped.reason"));
            assertNull(TraceStore.get("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempted"));
        } finally {
            GuardContextHolder.clear();
        }
    }

    @Test
    void searchWithTraceRunsQualityGateRescueWhenOfficialOnlyFallbackHasNoOfficialDocs() throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(1);
        props.setOfficialDocsRescueQueries(List.of("site:developers.openai.com {canonical}"));
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(2);
        GuardContextHolder.set(context);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "OpenAI Responses API web_search official evidence";
        AtomicInteger calls = new AtomicInteger();
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 4 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArgument(0);
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new NaverSearchService.SearchResult(
                        List.of("[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Community mirror https://community.example/openai"),
                        null);
            }
            assertTrue(String.valueOf(args[0]).startsWith("site:developers.openai.com "),
                    "searchWithTrace quality-gate rescue should use scoped official-docs query");
            return new NaverSearchService.SearchResult(
                    List.of("[WEB:DOCS|CRED:TRUSTED] Official docs https://developers.openai.com/api/docs/guides/tools-web-search"),
                    null);
        });

        try {
            Object result = assertDoesNotThrow(() -> aspect.aroundSearchWithTrace(pjp));

            assertTrue(result instanceof NaverSearchService.SearchResult);
            NaverSearchService.SearchResult sr = (NaverSearchService.SearchResult) result;
            assertEquals(2, calls.get(), "searchWithTrace should issue one quality-gate rescue provider call");
            assertTrue(sr.snippets().stream().anyMatch(s -> s.contains("developers.openai.com")),
                    String.valueOf(sr.snippets()));
            assertEquals(Boolean.TRUE,
                    TraceStore.get("web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempted"));
            assertEquals(Boolean.TRUE,
                    TraceStore.get("web.failsoft.starvationFallback.qualityGate.rescueInserted"));
        } finally {
            GuardContextHolder.clear();
        }
    }

    @Test
    void searchWithTraceQualityGateRescueWaitsThroughShortBraveCooldownBeforeRetry() throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(1);
        props.setOfficialDocsRescueQueries(List.of("site:developers.openai.com {canonical}"));
        RateLimitBackoffCoordinator backoff = mock(RateLimitBackoffCoordinator.class);
        when(backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE)).thenReturn(
                RateLimitBackoffCoordinator.Decision.allow(),
                RateLimitBackoffCoordinator.Decision.skip(5L, "local_rate_limit", false),
                RateLimitBackoffCoordinator.Decision.allow());
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(backoff));
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(2);
        GuardContextHolder.set(context);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "OpenAI changelog latest official docs";
        AtomicInteger calls = new AtomicInteger();
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 4 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new NaverSearchService.SearchResult(
                        List.of("[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Community mirror https://community.example/openai"),
                        null);
            }
            if (Boolean.TRUE.equals(TraceStore.get(
                    "web.failsoft.starvationFallback.qualityGate.rescueCooldownWait.attempted"))) {
                return new NaverSearchService.SearchResult(
                        List.of("[WEB:DOCS|CRED:TRUSTED] Official changelog https://openai.com/changelog/"),
                        null);
            }
            return new NaverSearchService.SearchResult(List.of(), null);
        });

        try {
            Object result = assertDoesNotThrow(() -> aspect.aroundSearchWithTrace(pjp));

            assertTrue(result instanceof NaverSearchService.SearchResult);
            NaverSearchService.SearchResult sr = (NaverSearchService.SearchResult) result;
            assertEquals(2, calls.get(), "rescue should retry once after the bounded cooldown wait");
            assertTrue(sr.snippets().stream().anyMatch(s -> s.contains("openai.com/changelog")),
                    String.valueOf(sr.snippets()));
            assertEquals(Boolean.TRUE, TraceStore.get(
                    "web.failsoft.starvationFallback.qualityGate.rescueCooldownWait.attempted"));
            assertEquals("brave", TraceStore.get(
                    "web.failsoft.starvationFallback.qualityGate.rescueCooldownWait.provider"));
            assertEquals(5L, TraceStore.getLong(
                    "web.failsoft.starvationFallback.qualityGate.rescueCooldownWait.remainingMs"));
        } finally {
            GuardContextHolder.clear();
        }
    }

    @Test
    void awaitEventSummaryDoesNotRetainRawEventLabels() {
        String rawEngine = "Private Engine ownerToken=raw-secret";
        String fakeKey = "sk-" + "12345678901234567890";
        String rawStep = "Private Step api_key=" + fakeKey;
        String rawCause = "skip_private raw query text";
        TraceStore.put("web.await.events", List.of(Map.of(
                "engine", rawEngine,
                "stage", "hard",
                "step", rawStep,
                "cause", rawCause,
                "waitedMs", 0L,
                "timeoutMs", 10L,
                "skip", true,
                "nonOk", true)));
        TraceStore.put("web.await.skipped.last", rawCause);
        TraceStore.put("web.await.skipped.last.engine", rawEngine);
        TraceStore.put("web.await.skipped.last.step", rawStep);

        ReflectionTestUtils.invokeMethod(WebFailSoftSearchAspect.class, "summarizeAwaitEventsForTrace");
        TraceStore.put("web.await.events", null);
        TraceStore.put("web.await.skipped.last", null);
        TraceStore.put("web.await.skipped.last.engine", null);
        TraceStore.put("web.await.skipped.last.step", null);

        String summary = String.valueOf(TraceStore.getAll());
        assertFalse(summary.contains(rawEngine), summary);
        assertFalse(summary.contains(rawStep), summary);
        assertFalse(summary.contains(rawCause), summary);
        assertFalse(summary.contains("raw-secret"), summary);
        assertFalse(summary.contains(fakeKey), summary);
        assertFalse(summary.contains("raw query text"), summary);
        assertTrue(summary.contains("hash:"), summary);
    }

    @Test
    void refreshRateLimitBackoffKpisDoesNotStoreRawCoordinatorReasons() {
        String fakeKey = "sk-" + "12345678901234567890";
        String rawReason = "private customer query ownerToken=" + fakeKey;
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, 1_000L, rawReason);
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(backoff));

        ReflectionTestUtils.invokeMethod(aspect, "refreshRateLimitBackoffKpis", 0);

        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains(rawReason), rendered);
        assertFalse(rendered.contains("private customer query"), rendered);
        assertFalse(rendered.contains(fakeKey), rendered);
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.rateLimitBackoff.naver.reason")).startsWith("hash:"),
                rendered);
    }

    @Test
    void soakKpiJsonReadsNamespacedStarvationTrigger() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("web.failsoft.starvationFallback.trigger", "officialOnly");

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9001L, Map.of(), List.of());

        assertEquals("officialOnly", lastStore.last().getKpi().get("starvationFallback.trigger"));
    }

    @Test
    void soakKpiJsonReadsStarvationPoolSafeEmptyAlias() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("starvationFallback.poolSafeEmpty", true);

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9009L, Map.of(), List.of());

        assertEquals(Boolean.TRUE, lastStore.last().getKpi().get("poolSafeEmpty"));
    }

    @Test
    void soakKpiJsonFallsBackToLastStageCountsWhenCanonicalKeyIsAbsent() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("stageCountsSelectedFromOut.last", Map.of("NOFILTER_SAFE", 1));

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9008L, null, List.of("safe result"));

        Map<String, Object> kpi = lastStore.last().getKpi();
        Map<?, ?> stageCounts = (Map<?, ?>) kpi.get("stageCountsSelectedFromOut");
        assertEquals(1, stageCounts.get("NOFILTER_SAFE"));
        assertEquals(1L, ((Number) kpi.get("stageCountsSelectedFromOut.NOFILTER_SAFE.total")).longValue());
    }

    @Test
    void soakOrchDigestIncludesSerpApiAndTavilySkippedReasons() {
        Map<String, Object> base = Map.of("providerStatus", "partial");
        Map<String, Object> withSerpApi = Map.of(
                "providerStatus", "partial",
                "web.serpapi.skipped.reason", "missing_serpapi_api_key");
        Map<String, Object> withTavily = Map.of(
                "providerStatus", "partial",
                "web.tavily.skipped.reason", "missing_tavily_api_key");

        String baseDigest = ReflectionTestUtils.invokeMethod(
                WebFailSoftSearchAspect.class, "computeOrchDigestForSoak", base);
        String serpApiDigest = ReflectionTestUtils.invokeMethod(
                WebFailSoftSearchAspect.class, "computeOrchDigestForSoak", withSerpApi);
        String tavilyDigest = ReflectionTestUtils.invokeMethod(
                WebFailSoftSearchAspect.class, "computeOrchDigestForSoak", withTavily);

        assertFalse(baseDigest.equals(serpApiDigest));
        assertFalse(baseDigest.equals(tavilyDigest));
    }

    @Test
    void soakKpiJsonDoesNotExportRawModelGuardIdentifiers() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        String rawRequested = "gpt-5-pro-private-owner-token";
        String rawSubstitute = "gpt-4.1-private-owner-token";
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.mode", "FAIL_FAST");
        TraceStore.put("llm.modelGuard.endpoint", "/v1/chat/completions");
        TraceStore.put("llm.modelGuard.failReason", "responses_only_on_chat_completions");
        TraceStore.put("llm.modelGuard.requestedModel", rawRequested);
        TraceStore.put("llm.modelGuard.substituteChatModel", rawSubstitute);
        TraceStore.put("llm.modelGuard.requestedModelHash", "hash:requested");
        TraceStore.put("llm.modelGuard.requestedModelLength", rawRequested.length());
        TraceStore.put("llm.modelGuard.substituteChatModelHash", "hash:substitute");
        TraceStore.put("llm.modelGuard.substituteChatModelLength", rawSubstitute.length());

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9006L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        String rendered = String.valueOf(kpi);
        assertFalse(rendered.contains(rawRequested), rendered);
        assertFalse(rendered.contains(rawSubstitute), rendered);
        assertFalse(kpi.containsKey("llm.modelGuard.requestedModel"), rendered);
        assertFalse(kpi.containsKey("llm.modelGuard.substituteChatModel"), rendered);
        assertEquals(Boolean.TRUE, kpi.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", kpi.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", kpi.get("llm.modelGuard.endpoint"));
        assertEquals("responses_only_on_chat_completions", kpi.get("llm.modelGuard.failReason"));
        assertEquals("hash:requested", kpi.get("llm.modelGuard.requestedModelHash"));
        assertEquals((long) rawRequested.length(),
                ((Number) kpi.get("llm.modelGuard.requestedModelLength")).longValue());
        assertEquals("hash:substitute", kpi.get("llm.modelGuard.substituteChatModelHash"));
        assertEquals((long) rawSubstitute.length(),
                ((Number) kpi.get("llm.modelGuard.substituteChatModelLength")).longValue());
    }

    @Test
    void soakKpiJsonDerivesTracePoolRescueMergeFromPoolItems() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 1));
        TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 2));

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9005L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertEquals(2L, ((Number) kpi.get("tracePool.size")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("rescueMerge.used"));
        assertEquals(2, TraceStore.getPoolItems().size());
    }

    @Test
    void soakKpiJsonEmitsProviderStatusMap() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        String rawReason = "disabled ownerToken=secret api_key=test-fixture";
        TraceStore.put("web.naver.skipped", true);
        TraceStore.put("web.naver.skipped.reason", rawReason);
        TraceStore.put("web.brave.skipped.reason", rawReason);
        TraceStore.put("web.serpapi.skipped", true);
        TraceStore.put("web.serpapi.skipped.reason", rawReason);
        TraceStore.put("web.naver.failureReason", "cache-only-rescue");
        TraceStore.put("web.brave.rateLimited", true);
        TraceStore.put("web.brave.retryAfterMs", 2500);
        TraceStore.put("web.serpapi.providerDisabled", true);
        TraceStore.put("web.serpapi.disabledReason", "missing_serpapi_api_key");
        TraceStore.put("web.serpapi.failureReason", "provider-disabled");
        TraceStore.put("web.serpapi.providerEmpty", false);
        TraceStore.put("web.serpapi.afterFilterStarved", false);
        TraceStore.put("web.serpapi.rateLimited", true);
        TraceStore.put("web.serpapi.retryAfterMs", 13000);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", rawReason);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", "missing_tavily_api_key");
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.retryAfterMs", 0);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.used", true);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.brave.count", 2);

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9002L, Map.of(), List.of("safe result"));

        Map<String, Object> kpi = lastStore.last().getKpi();
        Object provider = kpi.get("provider");
        assertTrue(provider instanceof Map<?, ?>, String.valueOf(kpi));
        Map<?, ?> providerMap = (Map<?, ?>) provider;
        assertEquals("skipped", providerMap.get("naver"));
        assertEquals("cache_only", providerMap.get("brave"));
        assertEquals("skipped", providerMap.get("serpapi"));
        assertEquals("skipped", providerMap.get("tavily"));
        assertTrue(String.valueOf(kpi.get("web.naver.skipped.reason")).startsWith("hash:"));
        assertTrue(String.valueOf(kpi.get("web.brave.skipped.reason")).startsWith("hash:"));
        assertTrue(String.valueOf(kpi.get("web.serpapi.skipped.reason")).startsWith("hash:"));
        assertEquals("cache-only-rescue", kpi.get("web.naver.failureReason"));
        assertEquals(Boolean.TRUE, kpi.get("web.brave.rateLimited"));
        assertEquals(2500L, ((Number) kpi.get("web.brave.retryAfterMs")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("web.serpapi.providerDisabled"));
        assertEquals("missing_serpapi_api_key", kpi.get("web.serpapi.disabledReason"));
        assertEquals("provider-disabled", kpi.get("web.serpapi.failureReason"));
        assertEquals(Boolean.FALSE, kpi.get("web.serpapi.providerEmpty"));
        assertEquals(Boolean.FALSE, kpi.get("web.serpapi.afterFilterStarved"));
        assertEquals(Boolean.TRUE, kpi.get("web.serpapi.rateLimited"));
        assertEquals(13000L, ((Number) kpi.get("web.serpapi.retryAfterMs")).longValue());
        assertTrue(String.valueOf(kpi.get("web.tavily.skipped.reason")).startsWith("hash:"));
        assertEquals(Boolean.TRUE, kpi.get("web.tavily.providerDisabled"));
        assertEquals("missing_tavily_api_key", kpi.get("web.tavily.disabledReason"));
        assertEquals("provider-disabled", kpi.get("web.tavily.failureReason"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.afterFilterStarved"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.rateLimited"));
        assertEquals(0L, ((Number) kpi.get("web.tavily.retryAfterMs")).longValue());
        String chain = String.valueOf(kpi.get("decisionChain"));
        assertTrue(chain.contains("serpapiSkipped"), chain);
        assertTrue(chain.contains("tavilySkipped"), chain);
        assertFalse(String.valueOf(kpi).contains(rawReason));
        assertFalse(String.valueOf(kpi).contains("ownerToken=secret"));
        assertFalse(String.valueOf(kpi).contains("api_key=test-fixture"));
    }

    @Test
    void soakKpiJsonExportsSerpApiAndTavilyAwaitTimeoutSummaryKeys() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("web.await.events.summary.engine.SerpApi.cause.await_timeout.count", 2L);
        TraceStore.put("web.await.events.summary.engine.Tavily.cause.await_timeout.count", 3L);

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9007L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertEquals(2L,
                ((Number) kpi.get("web.await.events.summary.engine.SerpApi.cause.await_timeout.count")).longValue());
        assertEquals(3L,
                ((Number) kpi.get("web.await.events.summary.engine.Tavily.cause.await_timeout.count")).longValue());
    }

    @Test
    void soakKpiJsonExportsSerpApiAndTavilyBackoffDetails() {
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(backoff));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("web.await.events.summary.engine.SerpApi.cause.await_timeout.count", 1L);
        TraceStore.put("web.await.events.summary.engine.Tavily.cause.await_timeout.count", 1L);

        ReflectionTestUtils.invokeMethod(aspect, "maybeApplyProviderBackoffFromAwaitSummary");
        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9017L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertTrue(((Number) kpi.get("web.failsoft.rateLimitBackoff.serpapi.remainingMs")).longValue() > 0L);
        assertTrue(((Number) kpi.get("web.failsoft.rateLimitBackoff.tavily.remainingMs")).longValue() > 0L);
        assertEquals("await_timeout", kpi.get("web.failsoft.rateLimitBackoff.serpapi.reason"));
        assertEquals("await_timeout", kpi.get("web.failsoft.rateLimitBackoff.tavily.reason"));
        assertEquals("AWAIT_TIMEOUT", kpi.get("web.failsoft.rateLimitBackoff.serpapi.last.kind"));
        assertEquals("AWAIT_TIMEOUT", kpi.get("web.failsoft.rateLimitBackoff.tavily.last.kind"));
        assertTrue(((Number) kpi.get("web.failsoft.rateLimitBackoff.serpapi.last.delayMs")).longValue() > 0L);
        assertTrue(((Number) kpi.get("web.failsoft.rateLimitBackoff.tavily.last.delayMs")).longValue() > 0L);
    }

    @Test
    void soakKpiOrchDigestIncludesSerpApiAndTavilyBackoffKinds() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);

        TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.last.kind", "AWAIT_TIMEOUT");
        TraceStore.put("web.failsoft.rateLimitBackoff.tavily.last.kind", "AWAIT_TIMEOUT");
        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9021L, Map.of(), List.of());
        String firstDigest = String.valueOf(lastStore.last().getKpi().get("orchDigest"));

        TraceStore.clear();
        TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.last.kind", "TIMEOUT");
        TraceStore.put("web.failsoft.rateLimitBackoff.tavily.last.kind", "TIMEOUT");
        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9022L, Map.of(), List.of());
        String secondDigest = String.valueOf(lastStore.last().getKpi().get("orchDigest"));

        assertFalse(firstDigest.equals(secondDigest), firstDigest + " / " + secondDigest);
    }

    @Test
    void soakKpiJsonCanonicalizesExplicitProviderScalarsToStableStateEnum() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        String rawProviderState = "disabled ownerToken=secret api_key=test-fixture";
        TraceStore.put("provider.naver", "rate-limit");
        TraceStore.put("provider.brave", rawProviderState);
        TraceStore.put("provider.serpapi", "cache-only");

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9004L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        Object provider = kpi.get("provider");
        assertTrue(provider instanceof Map<?, ?>, String.valueOf(kpi));
        Map<?, ?> providerMap = (Map<?, ?>) provider;
        assertEquals("skipped", providerMap.get("naver"));
        assertEquals("skipped", providerMap.get("brave"));
        assertEquals("cache_only", providerMap.get("serpapi"));
        assertEquals("cache_only", kpi.get("providerStatus"));
        assertEquals(Boolean.FALSE, kpi.get("kpi.next.allSkipped"));
        assertEquals(Boolean.FALSE, kpi.get("kpi.next.allSkipped.cacheOnlyRescueMissing"));
        assertFalse(String.valueOf(kpi).contains(rawProviderState));
        assertFalse(String.valueOf(kpi).contains("ownerToken=secret"));
        assertFalse(String.valueOf(kpi).contains("api_key=test-fixture"));
    }

    @Test
    void soakKpiJsonEmitsVectorFallbackLadderFields() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("retrieval.vectorFallback.used", true);
        TraceStore.put("retrieval.vectorFallback.reason", "web_empty");
        TraceStore.put("retrieval.vectorFallback.effectiveTopK", 10);

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9003L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertEquals(Boolean.TRUE, kpi.get("vectorFallback.used"));
        assertEquals("web_empty", kpi.get("vectorFallback.reason"));
        assertEquals(10L, ((Number) kpi.get("vectorFallback.effectiveTopK")).longValue());
        assertFalse(String.valueOf(kpi).contains("retrieval.vectorFallback.reason"));
    }

    @Test
    void soakKpiJsonReadsCanonicalVectorFallbackReasonAndTopK() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("vectorFallback.used", true);
        TraceStore.put("vectorFallback.reason", "web_empty");
        TraceStore.put("vectorFallback.effectiveTopK", 10);

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9010L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertEquals(Boolean.TRUE, kpi.get("vectorFallback.used"));
        assertEquals("web_empty", kpi.get("vectorFallback.reason"));
        assertEquals(10L, ((Number) kpi.get("vectorFallback.effectiveTopK")).longValue());
    }

    @Test
    void soakKpiJsonIncludesEcosystemRecirculationScalars() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        ReflectionTestUtils.setField(aspect, "webSoakKpiLastStore", lastStore);
        TraceStore.put("ecosystem.recirculate.used", true);
        TraceStore.put("ecosystem.recirculate.count", 3);
        TraceStore.put("ecosystem.recirculate.safe", 2);
        TraceStore.put("ecosystem.recirculate.allUnverified", false);
        TraceStore.put("ecosystem.pool.size", 4);
        TraceStore.put("ecosystem.recycled.total", 5L);
        TraceStore.put("ecosystem.ammonia.score", "0.33");
        TraceStore.put("ecosystem.ammonia.quarantined", 1);
        TraceStore.put("ecosystem.ammonia.safe", 2);
        TraceStore.put("ecosystem.ammonia.threshold", "0.50");
        TraceStore.put("ecosystem.ammonia.surgeBlocked", false);
        TraceStore.put("ecosystem.seed.snippet", "private snippet ownerToken=secret");

        ReflectionTestUtils.invokeMethod(aspect, "emitSoakKpiJson", 9006L, Map.of(), List.of());

        Map<String, Object> kpi = lastStore.last().getKpi();
        assertEquals(Boolean.TRUE, kpi.get("ecosystem.recirculate.used"));
        assertEquals(3L, ((Number) kpi.get("ecosystem.recirculate.count")).longValue());
        assertEquals(2L, ((Number) kpi.get("ecosystem.recirculate.safe")).longValue());
        assertEquals(Boolean.FALSE, kpi.get("ecosystem.recirculate.allUnverified"));
        assertEquals(4L, ((Number) kpi.get("ecosystem.pool.size")).longValue());
        assertEquals(5L, ((Number) kpi.get("ecosystem.recycled.total")).longValue());
        assertEquals("0.33", kpi.get("ecosystem.ammonia.score"));
        assertEquals(1L, ((Number) kpi.get("ecosystem.ammonia.quarantined")).longValue());
        assertEquals(2L, ((Number) kpi.get("ecosystem.ammonia.safe")).longValue());
        assertEquals("0.50", kpi.get("ecosystem.ammonia.threshold"));
        assertEquals(Boolean.FALSE, kpi.get("ecosystem.ammonia.surgeBlocked"));
        assertFalse(String.valueOf(kpi).contains("ownerToken=secret"));
        assertFalse(String.valueOf(kpi).contains("private snippet"));
    }

    @Test
    void planDeterministicStarvationLadderSortsEqualScoreQueriesByStableKey() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("starvationLadder.deterministic", true);
        List<String> candidates = new ArrayList<>(List.of("zeta neutral query", "alpha neutral query"));

        WebFailSoftRescueQuerySorter.sortOfficialDocsRescueQueries(
                candidates,
                context,
                "web.failsoft.test.rescueSort");

        assertEquals(List.of("alpha neutral query", "zeta neutral query"), candidates);
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.test.rescueSort.deterministic"));
        assertEquals(2, TraceStore.get("web.failsoft.test.rescueSort.candidateCount"));
    }

    @Test
    void qualityGateDebugEventSinkFailureLeavesRedactedTraceBreadcrumb() {
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-web-failsoft-event");
            }
        };
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                throwingStore,
                null,
                new FixedProvider<>(null));
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(2);
        RuleBasedQueryAugmenter.Augment augment = new RuleBasedQueryAugmenter.Augment(
                "official docs query",
                List.of("official docs query"),
                java.util.Set.of(),
                RuleBasedQueryAugmenter.Intent.GENERAL);

        List<String> out = assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                aspect,
                "applyStages",
                List.of("[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Private fallback https://community.example/path"),
                context,
                augment,
                2,
                "official docs query"));

        assertFalse(out.isEmpty());
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("web.failsoft.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("web_failsoft_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-web-failsoft-event"), trace);
    }

    @Test
    void ecosystemBufferRecirculatesSafeSnippetsWhenStageSelectionStarves() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        EcosystemBufferPool pool = new EcosystemBufferPool(8, 300_000L, 2);
        pool.charge("rid-test", List.of(
                WebSnippet.parse("Docs rescue https://docs.example.com/runtime"),
                WebSnippet.parse("Official rescue https://official.example.com/runtime")));
        ReflectionTestUtils.setField(aspect, "ecosystemBufferPool", pool);
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(1);
        RuleBasedQueryAugmenter.Augment augment = new RuleBasedQueryAugmenter.Augment(
                "official docs query",
                List.of("official docs query"),
                java.util.Set.of(),
                RuleBasedQueryAugmenter.Intent.GENERAL);

        List<String> out = ReflectionTestUtils.invokeMethod(
                aspect,
                "applyStages",
                List.of(),
                context,
                augment,
                2,
                "official docs query");

        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("[WEB:NOFILTER_SAFE|CRED:UNVERIFIED]"), String.valueOf(out));
        assertTrue(out.get(0).contains("https://docs.example.com/runtime"), String.valueOf(out));
        assertEquals(2, TraceStore.get("ecosystem.recirculate.count"));
        assertEquals(Boolean.TRUE, TraceStore.get("ecosystem.recirculate.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("starvationFallback.poolSafeEmpty"));
        assertEquals("ecosystem->NOFILTER_SAFE", TraceStore.get("starvationFallback.trigger"));
    }

    @Test
    void ecosystemBufferBlocksAllLowTrustRecirculationSurge() {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        EcosystemBufferPool pool = new EcosystemBufferPool(8, 300_000L, 4);
        pool.charge("rid-test", List.of(
                WebSnippet.parse("Low trust https://namu.wiki/w/runtime"),
                WebSnippet.parse("Low trust https://blog.naver.com/runtime")));
        ReflectionTestUtils.setField(aspect, "ecosystemBufferPool", pool);
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        context.setMinCitations(1);
        RuleBasedQueryAugmenter.Augment augment = new RuleBasedQueryAugmenter.Augment(
                "official docs query",
                List.of("official docs query"),
                java.util.Set.of(),
                RuleBasedQueryAugmenter.Intent.GENERAL);

        List<String> out = ReflectionTestUtils.invokeMethod(
                aspect,
                "applyStages",
                List.of(),
                context,
                augment,
                2,
                "official docs query");

        assertTrue(out.isEmpty(), String.valueOf(out));
        assertEquals("1.00", TraceStore.get("ecosystem.ammonia.score"));
        assertEquals(2, TraceStore.get("ecosystem.ammonia.quarantined"));
        assertEquals(0, TraceStore.get("ecosystem.ammonia.safe"));
        assertEquals(Boolean.TRUE, TraceStore.get("ecosystem.ammonia.surgeBlocked"));
        assertEquals(Boolean.TRUE, TraceStore.get("ecosystem.recirculate.allUnverified"));
        assertEquals(Boolean.TRUE, TraceStore.get("starvationFallback.poolSafeEmpty"));
    }

    @Test
    void qualityGateErrorTraceUsesRedactedMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.qualityGate.error\", e.toString())"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.qualityGate.error\", SafeRedactor.safeMessage(e.toString(), 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.qualityGate.error\", SafeRedactor.safeMessage(e.getMessage(), 240))"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.qualityGate.error\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
    }

    @Test
    void starvationFallbackUsedWritesCanonicalAliasAlongsideNamespacedKey() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.used\", true);"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.used\", true);"));
        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.used\", false);"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.used\", false);"));
    }

    @Test
    void starvationFallbackPoolAndCountWriteCanonicalAliasesAlongsideNamespacedKeys() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.poolUsed\", fallbackStage.name());"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.poolUsed\", fallbackStage.name());"));
        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.pool.safe.size\", safe == null ? 0 : safe.size());"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.pool.safe.size\", safe == null ? 0 : safe.size());"));
        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.pool.dev.size\", devRescue == null ? 0 : devRescue.size());"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.pool.dev.size\", devRescue == null ? 0 : devRescue.size());"));
        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.count\", String.valueOf(added));"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.count\", String.valueOf(added));"));
        assertTrue(source.contains("TraceStore.put(\"web.failsoft.starvationFallback.added\", added);"));
        assertTrue(source.contains("TraceStore.put(\"starvationFallback.added\", added);"));
    }

    @Test
    void traceStoreQueryFieldsUseDiagnosticSummaries() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertFalse(source.contains("ev.put(\"executedQuery\", safeTrim(canonical, 240))"));
        assertFalse(source.contains("ev.put(\"rescueQuery\", safeTrim(q2, 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.starvationFallback.qualityGate.rescueExtraQuery\", q2)"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.minCitationsRescue.rescueQuery\", q2)"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.minCitationsRescue.preflight.candidates.top3\",\n                        capList(new ArrayList<>(qCandidates), 3))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.extraQuery\", q2)"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.canonicalQuery\", aug.canonical())"));
        assertFalse(source.contains("data.put(\"executedQuery\", safeTrim(canonical, 240))"));
        assertFalse(source.contains("data.put(\"rescueQuery\", safeTrim(q2, 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.domainMisroute.query\", safeTrim(executedQuerySafe, 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.demotionLadder.executedQuery\", safeTrim(executedQuerySafe, 240))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.demotionLadder.canonicalQuery\", safeTrim(canonicalQuery, 240))"));
        assertFalse(source.contains("data.put(\"executedQuery\", safeTrim(executedQuerySafe, 240))"));
        assertFalse(source.contains("data.put(\"canonicalQuery\", safeTrim(canonicalQuery, 240))"));
        assertFalse(source.contains("ev.put(\"executedQuery\", safeTrim(executedQuery, 260))"));
        assertFalse(source.contains("ev.put(\"canonicalQuery\", safeTrim(canonicalQuery, 260))"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.failsoft.demotionLadder.key\", safeTrim(usedKey, 260))"));
        assertFalse(source.contains("ev.put(\"key\", safeTrim(usedKey, 260))"));
        assertFalse(source.contains("cand.put(\"url\", sn == null ? null : sn.url());"));
        assertFalse(source.contains("ev.put(\"url\", sn == null ? null : sn.url());"));
        assertFalse(source.contains("cand.put(\"key\", safeTrim(key, 280));"));
        assertFalse(source.contains("cand.put(\"tokenHits\", capList(tokenHits, 12));"));
        assertFalse(source.contains("cand.put(\"negHits\", capList(negHits, 12));"));
        assertFalse(source.contains("m.put(\"term\", safeTrim(term, 80));"));
        assertFalse(source.contains("m.put(\"slice\", safeTrim(sliceAround(baseText, pos, term.length(), window), 240));"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.starvationFallback.qualityGate.executedQuery\", canonical, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.starvationFallback.qualityGate.rescueQuery\", q2, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.starvationFallback.qualityGate.rescueExtraQuery\", q2, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.minCitationsRescue.rescueQuery\", q2, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.minCitationsRescue.preflight.queryCandidates.top3\", capList(new ArrayList<>(qCandidates), 3))"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.extraQuery\", q2, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.canonicalQuery\", aug.canonical(), 240)"));
        assertFalse(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.domainMisroute.query\", executedQuerySafe, 240)"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.failsoft.domainMisroute.queryHash\", SafeRedactor.hashValue(executedQuerySafe))"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.failsoft.domainMisroute.queryLength\","));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.demotionLadder.executedQuery\", executedQuerySafe, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.demotionLadder.canonicalQuery\", canonicalQuery, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.starvationFallback.qualityGate.executedQuery\", executedQuerySafe, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.starvationFallback.qualityGate.canonicalQuery\", canonicalQuery, 240)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.domainStagePairs.executedQuery\", executedQuery, 260)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.domainStagePairs.canonicalQuery\", canonicalQuery, 260)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.demotionLadder.key\", usedKey, 260)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.candidate.url\", sn == null ? null : sn.url(), 260)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.domainStagePairs.url\", sn == null ? null : sn.url(), 260)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.candidate.key\", key, 280)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.candidate.rawText.positiveHits\", capList(tokenHits, 12), 120)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.candidate.rawText.negativeHits\", capList(negHits, 12), 120)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.hitDetail.rawText.term\", term, 80)"));
        assertTrue(source.contains(
                "SafeRedactor.diagnosticValue(\"web.failsoft.hitDetail.rawText.slice\", sliceAround(baseText, pos, term.length(), window), 240)"));
    }

    @Test
    void failSoftExceptionLogsUseLowCardinalityFailureFields() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertFalse(source.contains(
                "qualityGate rescue search failed: {}\", com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains(
                "extra search failed: {}\", com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains(
                "GuardContextHolder missing (searchWithTrace): {}\", SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("qualityGate rescue search failed failureReason={} errorType={}"));
        assertTrue(source.contains("extra search failed failureReason={} errorType={}"));
        assertTrue(source.contains("GuardContextHolder missing (searchWithTrace) failureReason={} errorType={}"));
    }

    @Test
    void failSoftSearchFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));
        String helper = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftFailureTrace.java"));

        assertTrue(source.contains(
                "WebFailSoftFailureTrace.record(\"web.failsoft.starvationFallback.qualityGate.rescueSearch\", e, q2);"));
        assertTrue(source.contains(
                "WebFailSoftFailureTrace.record(\"web.failsoft.extraSearch\", e, q2);"));
        assertTrue(helper.contains("TraceStore.inc(prefix + \".failed.count\");"));
        assertTrue(helper.contains("TraceStore.put(prefix + \".failed\", true);"));
        assertTrue(helper.contains(
                "TraceStore.put(prefix + \".failureReason\", String.valueOf(NightmareBreaker.classify(failure)));"));
        assertTrue(helper.contains("TraceStore.put(prefix + \".errorType\","));
        assertTrue(helper.contains(
                "SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), \"unknown\")"));
        assertFalse(helper.contains("TraceStore.put(prefix + \".query\","));
        assertTrue(helper.contains("TraceStore.put(prefix + \".queryHash\", SafeRedactor.hashValue(query));"));
        assertTrue(helper.contains("TraceStore.put(prefix + \".queryLength\", query.length());"));
        assertFalse(helper.contains("catch (Throwable"));
        assertFalse(helper.contains("TraceStore.put(prefix + \".error\", String.valueOf(failure))"));
        assertFalse(helper.contains("TraceStore.put(prefix + \".query\", query)"));
    }

    @Test
    void failSoftSearchRescueCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webFailSoft.preflightBraveBackoff\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webFailSoft.webHardDownTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitationsRescue.preflightTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitationsRescue.queryAttempt\", ignoreOne);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitationsRescue.debugEvent\", ignoreOne);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitationsRescue.outer\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"qualityGate.rescueAttemptTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"qualityGate.outer\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"searchWithTrace.preflightBraveBackoff\", ignore);"));
    }

    @Test
    void failSoftSearchStageCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"applyStages.runInitTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"applyStages.minCitationsRead\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"officialOnlyClamp.includeDevCommunityTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"applyStages.classifyDetailed\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"profileBoost.deferredTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"citeableTopUp.insertMode\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"citeableTopUp.insertTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"citeableTopUp.summaryTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"citeableTopUp.unusedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.poolTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.citeableTargetTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.baseTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.capTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.severeTopUpTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.severeDesiredTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.citeableDesiredTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.addLimitTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.citeableAfterTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"starvationFallback.branchLog\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"profileBoost.deferredAfterFallbackTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"profileBoost.filledTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"officialOnlyStarved.log\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"officialOnlyStarved.breaker\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"candidateDiagnostics.fallbackEligibility\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"candidateDiagnostics.finalize\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"officialOnlyClamp.fallbackProps\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"officialOnlyClamp.evidenceTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"debugEvent.emitFailureTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"demotionLadder.scoreParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"demotionLadder.usedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"demotionLadder.eventTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"demotionLadder.noCandidateTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"failSoftRun.summaryTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"credibilityBoost.propsTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"credibilityBoost.countTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"declaredHeader.trim\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"declaredHeader.stageParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"declaredHeader.credParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"classifyByDomain.profileLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"credibility.declaredHeader\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"credibility.authorityLookup\", e);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitations.stageParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"minCitations.credParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"citeableTailDrop.stageParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"shouldStarvationFallback.rawInputCount\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"shouldStarvationFallback.skipReasonTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"failSoftMetrics.scoreParse\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"failSoftMetrics.record\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"stageList.unknownToken\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"awaitEvents.normalizeEvent\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"awaitEvents.castList\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"awaitEvents.normalizeOps\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"awaitEvents.contextRead\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.providerLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.naverAwaitDetected\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.naverResetTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.naverAwaitReconcileTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.braveAwaitDetected\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.braveResetTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.braveAwaitReconcileTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.applySummary\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.cancelledTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"providerBackoff.getAnyLong\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiSummary.dedupe\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiSummary.record\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"rateLimitBackoff.naverDecisionTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"rateLimitBackoff.braveDecisionTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"rateLimitBackoff.cooldownTradeoff\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpi.orchDigest\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.dedupe\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.rawInput\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.hybridFallback\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.partialDown\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.nofilterSafe\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.providerDigest\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.rateLimitBackoff\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.minCitationsPreflight\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.keywordSelection\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.awaitSummary\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.braveCooldown\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.modelGuard\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.starvationFallbackRatio\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.decisionChain\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.naverCooldownTransition\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.braveCooldownTransition\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.edgeTargetCount\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.qtxSoftCooldownKpi\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.nextAllSkippedKpi\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.remergeOnce\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.orchDigestStore\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.lastTraceStore\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.lastStore\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.warnJson\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.outer\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.naverBackfillTraceStore\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.braveBackfillTraceStore\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.backfillCoordinator\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.backfillOuter\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.starvationFallbackMaxRatio\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.nofilterSafeRatio\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.decisionChainMaxRatio\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"soakKpiJson.toLong\", ignore);"));
    }

    @Test
    void extraSearchFailureRecordsTraceBreadcrumbWithoutRawQueryOrThrowableMessage() throws Throwable {
        NovaWebFailSoftProperties props = new NovaWebFailSoftProperties();
        props.setAllowExtraSearchCalls(true);
        props.setMaxExtraSearchCalls(1);
        WebFailSoftSearchAspect aspect = new WebFailSoftSearchAspect(
                props,
                new RuleBasedQueryAugmenter(props),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new FixedProvider<>(null));
        String rawQuery = "Gemini API pricing private customer query ownerToken=secret";
        String rawError = "extra search failed for private customer query ownerToken=secret";
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 3 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArgument(0);
            if (rawQuery.equals(args[0])) {
                return List.of();
            }
            throw new IllegalStateException(rawError);
        });

        Object result = assertDoesNotThrow(() -> aspect.aroundSearch(pjp));

        assertEquals(List.of(), result);
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.extraSearch.failed"));
        assertEquals(1L, TraceStore.getLong("web.failsoft.extraSearch.failed.count"));
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.extraSearch.queryHash")).startsWith("hash:"));
        assertNull(TraceStore.get("web.failsoft.extraSearch.query"));
        assertTrue(((Number) TraceStore.get("web.failsoft.extraSearch.queryLength")).intValue() > 0);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawError), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void domainStagePairEventSummarizesUrlInsteadOfRawUrl() {
        String fakeKey = "sk-" + "test-12345678901234567890";
        String rawUrl = "https://example.com/path?api_key=" + fakeKey + "&user=alice";
        Map<String, Object> event = ReflectionTestUtils.invokeMethod(WebFailSoftSearchAspect.class, "stageEvent",
                7L,
                "private executed query",
                "private canonical query",
                "GENERAL",
                WebSnippet.parse("Title " + rawUrl),
                null,
                false,
                null);

        String rendered = String.valueOf(event);
        assertFalse(rendered.contains(rawUrl));
        assertFalse(rendered.contains(fakeKey));
        assertTrue(rendered.contains("example.com"));
        assertTrue(rendered.contains("hash12"));
    }

    @Test
    void hitDetailsSummarizeTermsAndSlicesInsteadOfRawText() {
        String fakeKey = "sk-" + "test-12345678901234567890";
        String privateTerm = "private anchor " + fakeKey;
        List<Map<String, Object>> details = ReflectionTestUtils.invokeMethod(WebFailSoftSearchAspect.class,
                "buildHitDetails",
                WebSnippet.parse("Title " + privateTerm + " https://example.com/a?token=secret"),
                List.of(privateTerm),
                6,
                36);

        String rendered = String.valueOf(details);
        assertFalse(rendered.contains(privateTerm));
        assertFalse(rendered.contains(fakeKey));
        assertFalse(rendered.contains("token=secret"));
        assertTrue(rendered.contains("hash12"));
        assertTrue(rendered.contains("len="));
    }

    @Test
    void numericDiagnosticParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java"));

        assertParserCallCatchNarrowed(source, "score = Integer.parseInt(String.valueOf(sc));");
        assertParserCallCatchNarrowed(source, "maxRatio = Double.parseDouble(String.valueOf(mr).trim());");
        assertParserCallCatchNarrowed(source,
                "nfs = Double.parseDouble(String.valueOf(j.getOrDefault(\"nofilterSafeRatio\", \"0\")));");
        assertParserCallCatchNarrowed(source, "return Long.parseLong(String.valueOf(o).trim());");
    }

    private static void assertParserCallCatchNarrowed(String source, String parserCall) {
        int from = 0;
        int hits = 0;
        while (true) {
            int parse = source.indexOf(parserCall, from);
            if (parse < 0) {
                break;
            }
            hits++;
            String window = source.substring(parse, Math.min(source.length(), parse + 260));
            assertFalse(window.contains("catch (Exception"),
                    "numeric fallback parser must not swallow all Exception near: " + parserCall);
            assertFalse(window.contains("catch (Throwable"),
                    "numeric fallback parser must not swallow Throwable near: " + parserCall);
            assertTrue(window.contains("catch (NumberFormatException"),
                    "numeric fallback parser should only catch NumberFormatException near: " + parserCall);
            from = parse + parserCall.length();
        }
        assertTrue(hits > 0, "parser call should be found: " + parserCall);
    }

    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }
    }
}
