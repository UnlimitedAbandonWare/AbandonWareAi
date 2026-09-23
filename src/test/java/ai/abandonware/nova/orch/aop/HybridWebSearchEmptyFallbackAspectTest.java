package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import ai.abandonware.nova.orch.failpattern.FailurePatternCooldownRegistry;
import ai.abandonware.nova.orch.failpattern.FailurePatternDetector;
import ai.abandonware.nova.orch.failpattern.FailurePatternJsonlWriter;
import ai.abandonware.nova.orch.failpattern.FailurePatternMetrics;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.web.BraveSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridWebSearchEmptyFallbackAspectTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void boundedSearchWithTraceSkipsEveryEmptyFallbackProviderCall() throws Throwable {
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(naver),
                new FixedProvider<>(brave),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                newOrchestrator());
        NaverSearchService.SearchResult empty = new NaverSearchService.SearchResult(List.of(), null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenAnswer(invocation -> {
            TraceStore.put("web.boundedRoute", true);
            return empty;
        });
        when(pjp.getArgs()).thenReturn(new Object[] { "bounded trace query", 3 });
        TraceStore.put("outCount", 0);

        Object result = aspect.aroundHybridSearchWithTrace(pjp);

        assertEquals(empty, result);
        verifyNoInteractions(naver, brave);
    }

    @Test
    void boundedSearchSkipsEveryEmptyFallbackProviderCall() throws Throwable {
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(naver),
                new FixedProvider<>(brave),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenAnswer(invocation -> {
            TraceStore.put("web.boundedRoute", true);
            return List.of();
        });
        when(pjp.getArgs()).thenReturn(new Object[] { "bounded query", 3 });
        TraceStore.put("web.await.events.nonOk.count", 1);

        Object result = aspect.aroundHybridSearch(pjp);

        assertEquals(List.of(), result);
        verifyNoInteractions(naver, brave);
    }

    @Test
    void cacheOnlyMissRecordsZeroMergedCount() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        TraceStore.put("cacheOnly.merged.count", 7);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 7);
        TraceStore.put("tracePool.size", 9);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 9);
        TraceStore.put("rescueMerge.used", true);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);
        TraceStore.put("poolSafeEmpty", true);
        TraceStore.put("starvationFallback.poolSafeEmpty", true);
        TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", true);

        Method method = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "cacheOnlyRescue",
                String.class,
                boolean.class,
                boolean.class,
                NaverSearchService.class,
                BraveSearchService.class,
                String.class,
                int.class);
        method.setAccessible(true);
        Object result = method.invoke(aspect, "final", true, true, null, null, "starvation trace query", 3);

        assertNull(result);
        assertEquals(0L, TraceStore.getLong("cacheOnly.merged.count"));
        assertEquals(0L, TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count"));
        assertEquals(0L, TraceStore.getLong("tracePool.size"));
        assertEquals(0L, TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size"));
        assertEquals(Boolean.FALSE, TraceStore.get("rescueMerge.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("poolSafeEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("starvationFallback.poolSafeEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty"));
    }

    @Test
    void cacheOnlyReentryMissClearsStaleMergedCount() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        Method method = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "cacheOnlyRescue",
                String.class,
                boolean.class,
                boolean.class,
                NaverSearchService.class,
                BraveSearchService.class,
                String.class,
                int.class);
        method.setAccessible(true);
        String query = "cache only reentry miss query";

        assertNull(method.invoke(aspect, "final", true, true, null, null, query, 3));
        TraceStore.put("cacheOnly.merged.count", 7);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 7);
        TraceStore.put("tracePool.size", 9);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 9);
        TraceStore.put("rescueMerge.used", true);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);

        assertNull(method.invoke(aspect, "final", true, true, null, null, query, 3));
        assertEquals(0L, TraceStore.getLong("cacheOnly.merged.count"));
        assertEquals(0L, TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count"));
        assertEquals(0L, TraceStore.getLong("tracePool.size"));
        assertEquals(0L, TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size"));
        assertEquals(Boolean.FALSE, TraceStore.get("rescueMerge.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used"));
    }

    @Test
    void pathScopedOfficialCacheOnlyRescueRejectsUnscopedGenericCacheHits() throws Exception {
        NaverSearchService naver = mock(NaverSearchService.class);
        when(naver.searchSnippetsCacheOnly(anyString(), anyInt())).thenAnswer(invocation -> {
            String probeQuery = invocation.getArgument(0, String.class);
            if (probeQuery != null && probeQuery.startsWith("site:developers.openai.com/api/docs/changelog")) {
                return List.of();
            }
            return List.of(
                    "[WEB:DOCS|CRED:TRUSTED] Generic tools docs https://developers.openai.com/api/docs/guides/tools-web-search",
                    "[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Community mirror https://community.example/openai");
        });
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(naver),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        Method method = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "cacheOnlyRescue",
                String.class,
                boolean.class,
                boolean.class,
                NaverSearchService.class,
                BraveSearchService.class,
                String.class,
                int.class);
        method.setAccessible(true);

        Object result = method.invoke(
                aspect,
                "final",
                false,
                true,
                naver,
                null,
                "site:developers.openai.com/api/docs/changelog OpenAI API changelog latest release notes official docs",
                3);

        assertNull(result, "path-scoped official queries must not reuse generic developer-doc cache hits");
        assertEquals(0L, TraceStore.getLong("cacheOnly.merged.count"));
        assertEquals(Boolean.TRUE,
                TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.enabled"));
        assertEquals(0L,
                TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.kept.count"));
        assertTrue(
                TraceStore.getLong("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.removed.count") >= 2L);
    }

    @Test
    void successfulRescuePublishesCanonicalStarvationTriggerWithoutRawQuery() throws Throwable {
        NaverSearchService naver = mock(NaverSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of("safe cached official result"));
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(naver),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                newOrchestrator());
        NaverSearchService.SearchResult empty = new NaverSearchService.SearchResult(List.of(), null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String rawQuery = "redaction probe query " + System.nanoTime();
        when(pjp.proceed()).thenReturn(empty);
        when(pjp.getArgs()).thenReturn(new Object[] { rawQuery, 3 });
        TraceStore.put("outCount", 0);

        Object result = aspect.aroundHybridSearchWithTrace(pjp);

        assertTrue(result instanceof NaverSearchService.SearchResult);
        assertEquals(List.of("safe cached official result"), ((NaverSearchService.SearchResult) result).snippets());
        assertEquals("hybridEmptyFallback->strict_filter_starvation",
                TraceStore.get("starvationFallback.trigger"));
        assertEquals("hybridEmptyFallback->strict_filter_starvation",
                TraceStore.get("web.failsoft.starvationFallback.trigger"));
        assertEquals("WEB_STARVATION", TraceStore.get("failpattern.web.starvation.kind"));
        assertEquals("web", TraceStore.get("failpattern.web.starvation.provider"));
        assertEquals("strict_filter_starvation", TraceStore.get("failpattern.web.starvation.ladderStage"));
        assertEquals(1L, TraceStore.get("failpattern.web.starvation.count"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
    }

    @Test
    void rescuePayloadCacheStaysInternalToTraceSnapshots() throws Exception {
        String resultKey = "web.failsoft.hybridEmptyFallback.result.abc123";
        String rawSecret = "sk-" + "hybrid-fallback-result-secret-1234567890";
        HybridWebSearchEmptyFallbackSupport.cacheRescuePayload(
                resultKey,
                List.of("private fallback result api_key=" + rawSecret));

        assertEquals(List.of("private fallback result api_key=" + rawSecret), TraceStore.get(resultKey));
        Map<String, Object> snapshot = TraceStore.getAll();
        String rendered = String.valueOf(snapshot);
        assertFalse(snapshot.containsKey(resultKey), rendered);
        assertFalse(rendered.contains(rawSecret), rendered);
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.hybridEmptyFallback.result.cached"));
        assertEquals(1, TraceStore.get("web.failsoft.hybridEmptyFallback.result.cached.size"));
        assertTrue(snapshot.containsKey("web.failsoft.hybridEmptyFallback.result.cached"));
    }

    @Test
    void fallbackSupportHelpersLiveOutsideAspectLargeFile() throws Exception {
        var aspectPath = java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java");
        var helperPath = java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackSupport.java");

        String aspect = java.nio.file.Files.readString(aspectPath);

        assertTrue(java.nio.file.Files.exists(helperPath),
                "cache probe, rescue-merge, and gate-hash helpers should live outside the aspect");
        String helper = java.nio.file.Files.readString(helperPath);
        assertTrue(aspect.contains("import static ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackSupport.*;"));
        assertFalse(aspect.contains("private static List<String> buildCacheProbeQueries("));
        assertFalse(aspect.contains("private static List<String> tracePoolRescueMerge("));
        assertFalse(aspect.contains("private static String queryHashForGate("));
        assertTrue(helper.contains("final class HybridWebSearchEmptyFallbackSupport"));
        assertTrue(helper.contains("static List<String> buildCacheProbeQueries("));
    }

    @Test
    void fallbackSupportSuppressedStageUsesSafeTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackSupport.java"));
        int start = source.indexOf("private static void logSuppressed");
        int end = source.indexOf("static String crc32Hex", start);
        String method = source.substring(start, end);

        assertFalse(method.contains("stage == null ? \"unknown\" : stage"));
        assertTrue(method.contains("String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertTrue(method.contains("log.debug(\"[hybrid-empty-fallback] suppressed stage={}\", safeStage);"));
    }

    @Test
    void hybridWebSearchEmptyFallbackAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "hybrid empty fallback fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void hybridEmptyFallbackLocalFailSoftCatchesUseTraceSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.guardContextEarly\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cachedResult\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cachedTopK\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.gateKeysTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.rootCauseTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.riskContext\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.triggerTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.nofilterSkippedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.demotionTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.demotedContextRestore\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.finalCacheOnlyTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.finalCacheOnlyNofilterUsedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.finalCacheOnlyNofilterTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.finalContextRestore\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.finalSafetyNet\", ignore);"));
    }

    @Test
    void hybridEmptyFallbackPollAndCacheOnlyCatchesUseTraceSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.noRescueSignals\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.noRescueTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.attemptTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.minLiveBudgetTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.providerFuture\", t);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cancelRemaining\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.completionPollTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.attemptResultTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.providerErrorTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.providerErrorFaultMask\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyGuardContext\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyZero100Context\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyReenterTrace\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyOnceTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyMpIntentTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyProbeTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyNaver\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyBrave\", ignore);"));
    }

    @Test
    void hybridEmptyFallbackUtilityCatchesUseTraceSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyMissTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.cacheOnlyHitTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.breakerOpenCheck\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.logOncePutIfAbsent\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.logOnceEmit\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.coldStart\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.coldStartAge\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.budgetExhaustedRaw0MissingRawMarker\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.budgetExhaustedRaw0\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.rateLimitCooldownSignal\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.markProviderSkippedBudgetExhaustedRaw0\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.getBoolean\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.getLong\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.getDouble\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallback.parseLong\", ignore);"));
    }

    @Test
    void hybridWebSearchEmptyFallbackSupportDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackSupport.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "hybrid empty fallback helper fail-soft blocks need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void hybridWebSearchEmptyFallbackSupportCatchesUseTraceSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackSupport.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.tracePoolSelected\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.tracePoolRaw\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.tracePoolTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.cacheRescuePayload\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.extractHost\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.crc32\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.crc32Fallback\", ignore2);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.collapseWhitespace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.stripBracketContent\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.normalizePunct\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.normalizeForProbeQuoteStrip\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.normalizeForProbeLower\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.asString\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"hybridEmptyFallbackSupport.normalizeForGateLower\", ignore);"));
    }

    @Test
    void rescueTraceTriggerLabelsDoNotExposeRawSensitiveAwaitParts() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        String rawSecret = "sk-" + "B".repeat(24);
        Method method = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "rescueEmptyHybridSearch",
                List.class,
                String.class,
                int.class,
                GuardContext.class,
                boolean.class,
                long.class,
                long.class,
                long.class,
                long.class,
                long.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                String.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);

        method.invoke(
                aspect,
                List.of(),
                "starvation recovery query",
                3,
                null,
                false,
                1L,
                0L,
                0L,
                0L,
                0L,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                "stage-token=" + rawSecret,
                "engine-ownerToken=private",
                "cause-api_key=" + rawSecret,
                "search");

        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains(rawSecret), rendered);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.hybridEmptyFallback.trigger.stage"))
                .startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.hybridEmptyFallback.trigger.engine"))
                .startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.failsoft.hybridEmptyFallback.trigger.cause"))
                .startsWith("hash:"));
    }

    @Test
    void providerCancellationUsesOperationalStatusAndTraceLabel() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new CancellationException("cancelled ownerToken=fake-token"));
        when(brave.searchWithMeta(anyString(), anyInt()))
                .thenThrow(new CancellationException("cancelled ownerToken=fake-token"));

        Method callNaver = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callNaver", NaverSearchService.class, String.class, int.class, long.class, long.class);
        callNaver.setAccessible(true);
        Object naverResult = callNaver.invoke(
                aspect,
                naver,
                "raw hybrid query ownerToken=fake-token",
                3,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
                250L);

        assertEquals("naver", resultValue(naverResult, "provider"));
        assertEquals("cancelled", resultValue(naverResult, "status"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));

        Method callBrave = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callBrave", BraveSearchService.class, String.class, int.class);
        callBrave.setAccessible(true);
        Object braveResult = callBrave.invoke(aspect, brave, "raw hybrid query ownerToken=fake-token", 3);

        assertEquals("brave", resultValue(braveResult, "provider"));
        assertEquals("cancelled", resultValue(braveResult, "status"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void wrappedProviderCancellationUsesCauseTypeNotRawThrowableText() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        RuntimeException wrappedCancel = new RuntimeException(
                "wrapped ownerToken=fake-token",
                new CancellationException("cancelled ownerToken=fake-token"));
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(wrappedCancel);
        when(brave.searchWithMeta(anyString(), anyInt()))
                .thenThrow(wrappedCancel);

        Method callNaver = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callNaver", NaverSearchService.class, String.class, int.class, long.class, long.class);
        callNaver.setAccessible(true);
        Object naverResult = callNaver.invoke(
                aspect,
                naver,
                "raw hybrid query ownerToken=fake-token",
                3,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
                250L);

        assertEquals("cancelled", resultValue(naverResult, "status"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));

        Method callBrave = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callBrave", BraveSearchService.class, String.class, int.class);
        callBrave.setAccessible(true);
        Object braveResult = callBrave.invoke(aspect, brave, "raw hybrid query ownerToken=fake-token", 3);

        assertEquals("cancelled", resultValue(braveResult, "status"));
        assertEquals("cancelled", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void providerTimeoutAndRateLimitUseOperationalStatusAndTraceLabel() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new RuntimeException("provider timeout ownerToken=fake-token"));
        when(brave.searchWithMeta(anyString(), anyInt()))
                .thenThrow(new RuntimeException("rate limit ownerToken=fake-token"));

        Method callNaver = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callNaver", NaverSearchService.class, String.class, int.class, long.class, long.class);
        callNaver.setAccessible(true);
        Object naverResult = callNaver.invoke(
                aspect,
                naver,
                "raw hybrid query ownerToken=fake-token",
                3,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
                250L);

        assertEquals("timeout", resultValue(naverResult, "status"));
        assertEquals("timeout", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));

        Method callBrave = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callBrave", BraveSearchService.class, String.class, int.class);
        callBrave.setAccessible(true);
        Object braveResult = callBrave.invoke(aspect, brave, "raw hybrid query ownerToken=fake-token", 3);

        assertEquals("rate-limit", resultValue(braveResult, "status"));
        assertEquals("rate-limit", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("RuntimeException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void providerUnknownFailuresUseStableOperationalStatus() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        NaverSearchService naver = mock(NaverSearchService.class);
        BraveSearchService brave = mock(BraveSearchService.class);
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new IllegalStateException("unexpected ownerToken=fake-token"));
        when(brave.searchWithMeta(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("unexpected ownerToken=fake-token"));

        Method callNaver = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callNaver", NaverSearchService.class, String.class, int.class, long.class, long.class);
        callNaver.setAccessible(true);
        Object naverResult = callNaver.invoke(
                aspect,
                naver,
                "raw hybrid query ownerToken=fake-token",
                3,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
                250L);

        assertEquals("provider-error", resultValue(naverResult, "status"));
        assertEquals("provider-error", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));

        Method callBrave = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "callBrave", BraveSearchService.class, String.class, int.class);
        callBrave.setAccessible(true);
        Object braveResult = callBrave.invoke(aspect, brave, "raw hybrid query ownerToken=fake-token", 3);

        assertEquals("provider-error", resultValue(braveResult, "status"));
        assertEquals("provider-error", TraceStore.get("web.failsoft.hybridEmptyFallback.error.last"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("IllegalStateException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void noRescueBreadcrumbFeedsPublicStarvationTriggerKpiWithoutRawQuery() throws Exception {
        HybridWebSearchEmptyFallbackAspect aspect = new HybridWebSearchEmptyFallbackAspect(
                new MockEnvironment(),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null),
                new FixedProvider<>(null));
        Method method = HybridWebSearchEmptyFallbackAspect.class.getDeclaredMethod(
                "recordNoRescueBreadcrumbs",
                String.class,
                String.class,
                int.class,
                long.class,
                long.class,
                long.class,
                long.class,
                long.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);

        method.invoke(
                aspect,
                "search",
                "private no rescue query ownerToken=secret",
                3,
                0L,
                0L,
                0L,
                0L,
                0L,
                false,
                false,
                false,
                true,
                true,
                false,
                "await",
                "Naver",
                "strict-domain");

        assertEquals("merged0.strict_filter_starve", TraceStore.get("end.classification"));
        assertEquals("noRescue:strict_filter_starve", TraceStore.get("starvationFallback.trigger"));
        assertEquals(0, TraceStore.get("cacheOnly.merged.count"));
        assertEquals(0, TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count"));
        assertEquals(0, TraceStore.get("tracePool.size"));
        assertEquals(0, TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size"));
        assertEquals(Boolean.FALSE, TraceStore.get("rescueMerge.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("poolSafeEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("starvationFallback.poolSafeEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private no rescue query"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void nofilterSafeLowTrustFilterRecordsAmmoniaSurgeTrace() throws Exception {
        List<String> filtered = HybridWebSearchEmptyFallbackSupport.maybeFilterLowTrustForNofilterSafe(
                List.of(
                        "Low trust https://namu.wiki/w/runtime",
                        "Low trust https://blog.naver.com/runtime",
                        "Safe docs https://docs.example.com/runtime"),
                3,
                true,
                0.55d,
                0.5d,
                "UNVERIFIED");

        assertEquals(2, filtered.size());
        assertEquals("0.67", TraceStore.get("ecosystem.ammonia.score"));
        assertEquals(2, TraceStore.get("ecosystem.ammonia.quarantined"));
        assertEquals(1, TraceStore.get("ecosystem.ammonia.safe"));
        assertEquals(Boolean.TRUE, TraceStore.get("ecosystem.ammonia.surgeBlocked"));
        assertEquals("UNVERIFIED", TraceStore.get("ecosystem.ammonia.quarantineTag"));
    }

    @Test
    void numericConfigParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"));

        assertParserCatchNarrowed(source, "private long getLong");
        assertParserCatchNarrowed(source, "private double getDouble");
        assertParserCatchNarrowed(source, "private static long parseLong");
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse > start, "parser call should be locatable: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be locatable: " + signature);
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(helper.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void logicalFallbackDeadlineDoesNotTerminateAnAlreadyStartedProviderOperation() throws Exception {
        TraceStore.clear();
        BraveSearchService brave = mock(BraveSearchService.class);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var operationStart = new java.util.concurrent.atomic.AtomicLong();
        var operationEnd = new java.util.concurrent.atomic.AtomicLong();
        when(brave.isEnabled()).thenReturn(true);
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(invocation -> {
            operationStart.set(System.nanoTime());
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("provider release deadline exceeded");
                return null;
            } catch (InterruptedException failure) {
                interrupted.set(true);
                throw failure;
            } finally {
                operationEnd.set(System.nanoTime());
                exited.countDown();
                TraceStore.clear();
            }
        });
        var environment = new MockEnvironment()
                .withProperty("nova.orch.web.failsoft.hybrid-empty-fallback.min-live-budget-ms", "0");
        var aspect = new HybridWebSearchEmptyFallbackAspect(environment, new FixedProvider<>(null),
                new FixedProvider<>(brave), new FixedProvider<>(executor), new FixedProvider<>(null), new FixedProvider<>(null));
        try {
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            long invocationStart = System.nanoTime();
            long deadline = invocationStart + TimeUnit.MILLISECONDS.toNanos(500);
            Object result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(aspect, "attemptFallback",
                    "synthetic-lifetime", null, brave, executor, null, "synthetic bounded query", 3, deadline,
                    true, false, 0L, 0L, 0L, 0L, 0L, false, "synthetic", "synthetic", "synthetic");
            long returnedAt = System.nanoTime();
            assertTrue(started.await(1, TimeUnit.SECONDS), "observe the actual provider body, not a Future state");
            long logicalMs = TimeUnit.NANOSECONDS.toMillis(returnedAt - invocationStart);
            assertTrue(logicalMs < 2_000L, "completion polling must return without waiting for provider release");
            assertTrue(((List<?>) resultValue(result, "merged")).isEmpty());
            assertFalse(exited.await(100, TimeUnit.MILLISECONDS), "backing operation must still be blocked after fallback returns");
            assertFalse(interrupted.get(), "cancel(false) must preserve the no-interrupt policy");
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertTrue(operationStart.get() < returnedAt && operationEnd.get() > returnedAt);
            org.mockito.Mockito.verify(brave, org.mockito.Mockito.times(1)).searchWithMeta(anyString(), anyInt());
            System.out.println("F28_LIFETIME_COUNTS providerStarted=1 runningAfterFallback=1 exitedAfterRelease=1"
                    + " workerInterrupted=" + interrupted.get() + " logicalElapsedMs=" + logicalMs
                    + " backingElapsedMs=" + TimeUnit.NANOSECONDS.toMillis(operationEnd.get() - operationStart.get()));
        } finally {
            release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "owned executor must terminate");
        }
    }

    private static Object resultValue(Object result, String fieldName) throws Exception {
        Field field = result.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(result);
    }

    private static FailurePatternOrchestrator newOrchestrator() {
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        props.getJsonl().setReadEnabled(false);
        props.getJsonl().setWriteEnabled(false);

        return new FailurePatternOrchestrator(
                new FailurePatternDetector(),
                new FailurePatternMetrics(null, props),
                new FailurePatternJsonlWriter(new ObjectMapper(), props),
                new FailurePatternCooldownRegistry(),
                new ObjectMapper(),
                props);
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
