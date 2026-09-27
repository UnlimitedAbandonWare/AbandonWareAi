package ai.abandonware.nova.orch.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSoakKpiProbeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
    }

    @Test
    void runSamplesExposeFixedKpiFieldsWithoutRawQuery() throws Exception {
        String rawQuery = "private soak query ownerToken=secret";
        String rawSecret = "sk-" + "12345678901234567890";
        String rawReason = "private probe reason api_key=" + rawSecret;
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 2);
            TraceStore.put("web.failsoft.rawInputCount", 3);
            TraceStore.put("provider.naver", "skipped");
            TraceStore.put("provider.brave", "cache_only");
            TraceStore.put("provider.serpapi", "skipped");
            TraceStore.put("provider.tavily", "skipped");
            TraceStore.put("web.naver.skipped.reason", rawReason);
            TraceStore.put("web.brave.skipped.reason", rawReason);
            TraceStore.put("web.serpapi.skipped.reason", rawReason);
            TraceStore.put("web.tavily.skipped.reason", rawReason);
            TraceStore.put("web.failsoft.rateLimitBackoff.naver.reason", rawReason);
            TraceStore.put("web.failsoft.rateLimitBackoff.brave.reason", rawReason);
            TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.reason", rawReason);
            TraceStore.put("web.failsoft.rateLimitBackoff.tavily.reason", rawReason);
            TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.remainingMs", 1200L);
            TraceStore.put("web.failsoft.rateLimitBackoff.tavily.remainingMs", 900L);
            TraceStore.put("stageCountsSelectedFromOut", Map.of("NOFILTER_SAFE", 1));
            TraceStore.put("cacheOnly.merged.count", 5);
            TraceStore.put("tracePool.size", 6);
            TraceStore.put("rescueMerge.used", true);
            TraceStore.put("retrieval.vectorFallback.used", true);
            TraceStore.put("retrieval.vectorFallback.reason", "web_empty");
            TraceStore.put("retrieval.vectorFallback.effectiveTopK", 10);
            TraceStore.put("starvationFallback.trigger", "officialOnly");
            TraceStore.put("poolSafeEmpty", false);
            return List.of("safe result");
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of(rawQuery));

        WebSoakKpiProbeService.Report report = service.run(request);

        assertEquals(1, report.getSamples().size());
        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        assertEquals(Boolean.TRUE, sample.get("queryPresent"));
        assertEquals(rawQuery.length(), sample.get("queryLength"));
        assertEquals(SafeRedactor.hash12(rawQuery), sample.get("queryHash12"));
        assertNotNull(sample.get("rid"));
        assertNotNull(sample.get("sessionId"));
        assertTrue(String.valueOf(sample.get("rid")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("sessionId")).startsWith("hash:"));

        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertTrue(String.valueOf(kpi.get("rid")).startsWith("hash:"));
        assertTrue(String.valueOf(kpi.get("sessionId")).startsWith("hash:"));
        assertEquals("skipped", kpi.get("provider.naver"));
        assertEquals("cache_only", kpi.get("provider.brave"));
        assertEquals("skipped", kpi.get("provider.serpapi"));
        assertEquals("skipped", kpi.get("provider.tavily"));
        Map<?, ?> provider = assertInstanceOf(Map.class, kpi.get("provider"));
        assertEquals("skipped", provider.get("naver"));
        assertEquals("cache_only", provider.get("brave"));
        assertEquals("skipped", provider.get("serpapi"));
        assertEquals("skipped", provider.get("tavily"));
        assertEquals(2L, ((Number) kpi.get("outCount")).longValue());
        assertEquals(5L, ((Number) kpi.get("cacheOnly.merged.count")).longValue());
        assertEquals(6L, ((Number) kpi.get("tracePool.size")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("rescueMerge.used"));
        assertEquals(Boolean.TRUE, kpi.get("vectorFallback.used"));
        assertEquals("web_empty", kpi.get("vectorFallback.reason"));
        assertEquals(10L, ((Number) kpi.get("vectorFallback.effectiveTopK")).longValue());
        assertEquals("officialOnly", kpi.get("starvationFallback.trigger"));
        assertEquals(Boolean.FALSE, kpi.get("poolSafeEmpty"));
        assertTrue(String.valueOf(sample.get("naverSkippedReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("braveSkippedReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("serpapiSkippedReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("tavilySkippedReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("naverBackoffReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("braveBackoffReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("serpapiBackoffReason")).startsWith("hash:"));
        assertTrue(String.valueOf(sample.get("tavilyBackoffReason")).startsWith("hash:"));
        assertEquals(1200L, ((Number) sample.get("serpapiBackoffRemainingMs")).longValue());
        assertEquals(900L, ((Number) sample.get("tavilyBackoffRemainingMs")).longValue());

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawQuery));
        assertFalse(serialized.contains(rawReason));
        assertFalse(serialized.contains("private probe reason"));
        assertFalse(serialized.contains(rawSecret));
        assertFalse(serialized.contains("ownerToken=secret"));
        assertFalse(serialized.contains("safe result"));
        assertTrue(serialized.contains(SafeRedactor.hash12(rawQuery)));
    }

    @Test
    void runSamplesDropNonFiniteNumericKpiTraceValues() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", Double.POSITIVE_INFINITY);
            TraceStore.put("rescueMerge.used", Double.POSITIVE_INFINITY);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private nonfinite kpi query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(0L, ((Number) kpi.get("outCount")).longValue());
        assertEquals(Boolean.FALSE, kpi.get("rescueMerge.used"));
    }

    @Test
    void runSamplesMarkOnlyProviderWithTraceNamespaceAsObserved() {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.naver.returnedCount", 0);
            TraceStore.put("web.naver.providerEmpty", false);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("provider observation contract"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("web.naver.traceObserved"));
        assertEquals(Boolean.FALSE, kpi.get("web.brave.traceObserved"));
        assertEquals(Boolean.FALSE, kpi.get("web.serpapi.traceObserved"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.traceObserved"));
        assertFalse(kpi.containsKey("web.naver.evidenceObserved"));
        assertEquals(0L, ((Number) kpi.get("web.naver.returnedCount")).longValue());
        assertEquals(Boolean.FALSE, kpi.get("web.naver.providerEmpty"));
        assertNull(kpi.get("web.naver.requestedCount"));
        assertNull(kpi.get("web.brave.returnedCount"));
        assertNull(kpi.get("web.brave.providerEmpty"));
    }

    @Test
    void runSamplesKeepMalformedProviderTaxonomyUnobservedAtMetricLevel() throws Exception {
        String rawValue = "private taxonomy value ownerToken=secret";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.naver.requestedCount", Double.NaN);
            TraceStore.put("web.naver.providerEmpty", rawValue);
            TraceStore.put("web.naver.failureReason", rawValue);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("malformed provider taxonomy contract"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("web.naver.traceObserved"));
        assertNull(kpi.get("web.naver.requestedCount"));
        assertNull(kpi.get("web.naver.providerEmpty"));
        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawValue));
        assertFalse(serialized.contains("ownerToken=secret"));
    }

    @Test
    void runSamplesInferProviderMapFromSkipAndCacheOnlyTraceWhenProviderScalarsMissing() throws Exception {
        String rawBraveReason = "disabled ownerToken=secret api_key=test-fixture";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("web.failsoft.rawInputCount", 0);
            TraceStore.put("web.naver.skipped", true);
            TraceStore.put("web.brave.skipped", true);
            TraceStore.put("web.serpapi.skipped", true);
            TraceStore.put("web.tavily.skipped", true);
            TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
            TraceStore.put("web.naver.requestedCount", 10);
            TraceStore.put("web.naver.returnedCount", 3);
            TraceStore.put("web.naver.afterFilterCount", 0);
            TraceStore.put("web.naver.timeout", true);
            TraceStore.put("web.naver.timeoutMs", 1500L);
            TraceStore.put("web.brave.skipped.reason", rawBraveReason);
            TraceStore.put("web.brave.cancelled", true);
            TraceStore.put("web.brave.exceptionType", "cancelled");
            TraceStore.put("web.serpapi.skipped.reason", "missing_key");
            TraceStore.put("web.tavily.skipped.reason", "missing_tavily_api_key");
            TraceStore.put("web.serpapi.providerDisabled", true);
            TraceStore.put("web.serpapi.disabledReason", "missing_serpapi_api_key");
            TraceStore.put("web.serpapi.failureReason", "provider-disabled");
            TraceStore.put("web.serpapi.providerEmpty", false);
            TraceStore.put("web.serpapi.afterFilterStarved", false);
            TraceStore.put("web.serpapi.rateLimited", true);
            TraceStore.put("web.serpapi.retryAfterMs", 13000L);
            TraceStore.put("web.tavily.providerDisabled", true);
            TraceStore.put("web.tavily.disabledReason", "missing_tavily_api_key");
            TraceStore.put("web.tavily.failureReason", "provider-disabled");
            TraceStore.put("web.tavily.providerEmpty", false);
            TraceStore.put("web.tavily.afterFilterStarved", false);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.used", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.naver.count", 1);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 1);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 4);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private all skipped cache-only query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        Map<?, ?> provider = assertInstanceOf(Map.class, kpi.get("provider"));
        assertEquals("cache_only", provider.get("naver"));
        assertEquals("skipped", provider.get("brave"));
        assertEquals("skipped", provider.get("serpapi"));
        assertEquals("skipped", provider.get("tavily"));
        assertEquals("cache_only", kpi.get("provider.naver"));
        assertEquals("skipped", kpi.get("provider.brave"));
        assertEquals("skipped", kpi.get("provider.serpapi"));
        assertEquals("skipped", kpi.get("provider.tavily"));
        assertEquals("breaker_open_or_half_open", kpi.get("web.naver.skipped.reason"));
        assertEquals(10L, ((Number) kpi.get("web.naver.requestedCount")).longValue());
        assertEquals(3L, ((Number) kpi.get("web.naver.returnedCount")).longValue());
        assertEquals(0L, ((Number) kpi.get("web.naver.afterFilterCount")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("web.naver.timeout"));
        assertEquals(1500L, ((Number) kpi.get("web.naver.timeoutMs")).longValue());
        assertTrue(String.valueOf(kpi.get("web.brave.skipped.reason")).startsWith("hash:"));
        assertEquals(Boolean.TRUE, kpi.get("web.brave.cancelled"));
        assertEquals("cancelled", kpi.get("web.brave.exceptionType"));
        assertEquals("missing_key", kpi.get("web.serpapi.skipped.reason"));
        assertEquals("missing_tavily_api_key", kpi.get("web.tavily.skipped.reason"));
        assertEquals(Boolean.TRUE, kpi.get("web.serpapi.providerDisabled"));
        assertEquals("missing_serpapi_api_key", kpi.get("web.serpapi.disabledReason"));
        assertEquals("provider-disabled", kpi.get("web.serpapi.failureReason"));
        assertEquals(Boolean.FALSE, kpi.get("web.serpapi.providerEmpty"));
        assertEquals(Boolean.FALSE, kpi.get("web.serpapi.afterFilterStarved"));
        assertEquals(Boolean.TRUE, kpi.get("web.serpapi.rateLimited"));
        assertEquals(13000L, ((Number) kpi.get("web.serpapi.retryAfterMs")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("web.tavily.providerDisabled"));
        assertEquals("missing_tavily_api_key", kpi.get("web.tavily.disabledReason"));
        assertEquals("provider-disabled", kpi.get("web.tavily.failureReason"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, kpi.get("web.tavily.afterFilterStarved"));
        assertEquals(1L, ((Number) kpi.get("cacheOnly.merged.count")).longValue());
        assertEquals(4L, ((Number) kpi.get("tracePool.size")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("rescueMerge.used"));

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawBraveReason));
        assertFalse(serialized.contains("ownerToken=secret"));
        assertFalse(serialized.contains("api_key=test-fixture"));
    }

    @Test
    void runSamplesUseLastStageCountsWhenCanonicalKeyIsAbsent() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 1);
            TraceStore.put("stageCountsSelectedFromOut.last", Map.of("NOFILTER_SAFE", 1));
            return List.of("safe result");
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private last stage count query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        Map<?, ?> stageCounts = assertInstanceOf(Map.class, kpi.get("stageCountsSelectedFromOut"));
        assertEquals(1, stageCounts.get("NOFILTER_SAFE"));
    }

    @Test
    void runSamplesCanonicalizeExplicitProviderScalarsToStableStateEnum() throws Exception {
        String rawProviderState = "disabled ownerToken=secret api_key=test-fixture";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("provider.naver", "rate-limit");
            TraceStore.put("provider.brave", rawProviderState);
            TraceStore.put("provider.serpapi", "cache-only");
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private provider enum query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        Map<?, ?> provider = assertInstanceOf(Map.class, kpi.get("provider"));
        assertEquals("skipped", provider.get("naver"));
        assertEquals("skipped", provider.get("brave"));
        assertEquals("cache_only", provider.get("serpapi"));
        assertEquals("skipped", kpi.get("provider.naver"));
        assertEquals("skipped", kpi.get("provider.brave"));
        assertEquals("cache_only", kpi.get("provider.serpapi"));
        assertEquals("cache_only", kpi.get("providerStatus"));
        assertEquals(Boolean.FALSE, kpi.get("kpi.next.allSkipped"));
        assertEquals(Boolean.FALSE, kpi.get("kpi.next.allSkipped.cacheOnlyRescueMissing"));

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawProviderState));
        assertFalse(serialized.contains("ownerToken=secret"));
        assertFalse(serialized.contains("api_key=test-fixture"));
    }

    @Test
    void runSamplesExposeAllSkippedCacheOnlyRescueMissingHint() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("web.naver.skipped", true);
            TraceStore.put("web.brave.skipped", true);
            TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
            TraceStore.put("web.brave.skipped.reason", "rate_limit");
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private all skipped no rescue query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("kpi.next.allSkipped"));
        assertEquals(Boolean.TRUE, kpi.get("kpi.next.allSkipped.cacheOnlyRescueMissing"));
    }

    @Test
    void aggregateCountsAnyBraveSkippedReasonSeparatelyFromLiteralDisabled() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("web.failsoft.rawInputCount", 0);
            TraceStore.put("web.brave.skipped.reason", "quota_exhausted_or_disabled");
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private brave skipped aggregate query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> current = objectMapper.convertValue(
                report.getCurrent(),
                new TypeReference<>() {
                });
        assertEquals(0L, ((Number) current.get("braveDisabledCount")).longValue());
        assertEquals(1L, ((Number) current.get("braveSkippedCount")).longValue());
        assertTrue(report.getTable().contains("web.brave.skipped (any reason)"));
        assertFalse(report.getTable().contains("web.brave.skipped.reason=disabled"));
    }

    @Test
    void aggregateCountsSerpApiAndTavilyAwaitTimeoutBackoffInGenericMetric() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.reason", "await_timeout");
            TraceStore.put("web.failsoft.rateLimitBackoff.tavily.reason", "await_timeout");
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private provider await timeout query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> current = objectMapper.convertValue(
                report.getCurrent(),
                new TypeReference<>() {
                });
        assertEquals(0L, ((Number) current.get("naverAwaitTimeoutBackoffCount")).longValue());
        assertEquals(2L, ((Number) current.get("providerAwaitTimeoutBackoffCount")).longValue());
        assertTrue(report.getTable().contains("ProviderBackoff (any await_timeout)"));
        assertTrue(report.getTable().contains("Provider await_timeout backoff -> 0"));
        assertTrue(report.getTuningHints().stream()
                .anyMatch(h -> h.contains("Provider await_timeout backoff detected")));
    }

    @Test
    void runSamplesExposeNamespacedStarvationTrigger() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("web.failsoft.starvationFallback.trigger", "officialOnly");
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private namespaced trigger query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals("officialOnly", kpi.get("starvationFallback.trigger"));
    }

    @Test
    void runSamplesExposeDetailedStarvationFallbackKpisFromNamespacedAliases() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("web.failsoft.starvationFallback.used", true);
            TraceStore.put("web.failsoft.starvationFallback.poolUsed", "NOFILTER_SAFE");
            TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", 3);
            TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", 1);
            TraceStore.put("web.failsoft.starvationFallback.count", "2");
            TraceStore.put("web.failsoft.starvationFallback.added", 2);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private starvation detail query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("starvationFallback.used"));
        assertEquals("NOFILTER_SAFE", kpi.get("starvationFallback.poolUsed"));
        assertEquals(3L, ((Number) kpi.get("starvationFallback.pool.safe.size")).longValue());
        assertEquals(1L, ((Number) kpi.get("starvationFallback.pool.dev.size")).longValue());
        assertEquals(2L, ((Number) kpi.get("starvationFallback.count")).longValue());
        assertEquals(2L, ((Number) kpi.get("starvationFallback.added")).longValue());

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains("private starvation detail query"), serialized);
        assertFalse(serialized.contains("ownerToken=secret"), serialized);
    }

    @Test
    void runSamplesExposeStarvationPoolSafeEmptyAlias() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("starvationFallback.poolSafeEmpty", true);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private pool safe empty alias query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("poolSafeEmpty"));
    }

    @Test
    void runSamplesDeriveTracePoolRescueMergeFromPoolItems() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 1));
            TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 2));
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private trace pool derivation query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(2L, ((Number) kpi.get("tracePool.size")).longValue());
        assertEquals(Boolean.TRUE, kpi.get("rescueMerge.used"));
    }

    @Test
    void runSamplesExposeCanonicalVectorFallbackReasonAndTopK() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
            TraceStore.put("vectorFallback.used", true);
            TraceStore.put("vectorFallback.reason", "web_empty");
            TraceStore.put("vectorFallback.effectiveTopK", 10);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private vector fallback alias query ownerToken=secret"));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("vectorFallback.used"));
        assertEquals("web_empty", kpi.get("vectorFallback.reason"));
        assertEquals(10L, ((Number) kpi.get("vectorFallback.effectiveTopK")).longValue());
    }

    @Test
    void runSamplesExposeEcosystemRecirculationKpiScalarsWithoutRawText() throws Exception {
        String rawQuery = "private ecosystem soak query ownerToken=secret";
        String rawSeedSnippet = "bounded recirculation smoke seed Authorization: Bearer secret";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failsoft.outCount", 0);
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
            TraceStore.put("starvationFallback.trigger", "BELOW_MIN_CITATIONS");
            TraceStore.put("poolSafeEmpty", true);
            TraceStore.put("ecosystem.seed.snippet", rawSeedSnippet);
            return List.of();
        });

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of(rawQuery));

        WebSoakKpiProbeService.Report report = service.run(request);

        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
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
        assertEquals("ecosystem->NOFILTER_SAFE", kpi.get("starvationFallback.trigger"));
        assertEquals(Boolean.FALSE, kpi.get("poolSafeEmpty"));

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawQuery));
        assertFalse(serialized.contains(rawSeedSnippet));
        assertFalse(serialized.contains("ownerToken=secret"));
        assertFalse(serialized.contains("Authorization"));
    }

    @Test
    void runSampleCancellationUsesOperationalErrorClassWithoutRawQuery() throws Exception {
        String rawQuery = "private soak cancelled query ownerToken=secret";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt()))
                .thenThrow(new CancellationException("cancelled ownerToken=secret query=" + rawQuery));

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of(rawQuery));

        WebSoakKpiProbeService.Report report = service.run(request);

        assertEquals(1, report.getSamples().size());
        String error = report.getSamples().get(0).getError();
        assertTrue(error.startsWith("cancelled#"), error);
        assertEquals("cancelled#".length() + 12, error.length(), error);

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawQuery));
        assertFalse(serialized.contains("CancellationException"), serialized);
        assertFalse(serialized.contains("ownerToken=secret"), serialized);
    }

    @Test
    void runSampleWrappedCancellationUsesCauseTypeWithoutRawQuery() throws Exception {
        String rawQuery = "private soak wrapped cancelled query ownerToken=secret";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt()))
                .thenThrow(new RuntimeException(
                        "wrapper ownerToken=secret",
                        new CancellationException("cancelled ownerToken=secret query=" + rawQuery)));

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of(rawQuery));

        WebSoakKpiProbeService.Report report = service.run(request);

        String error = report.getSamples().get(0).getError();
        assertTrue(error.startsWith("cancelled#"), error);
        assertEquals("cancelled#".length() + 12, error.length(), error);

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawQuery));
        assertFalse(serialized.contains("RuntimeException"), serialized);
        assertFalse(serialized.contains("CancellationException"), serialized);
        assertFalse(serialized.contains("ownerToken=secret"), serialized);
    }

    @Test
    void runSampleRestoresTraceContextAfterProbeCall() throws Exception {
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt())).thenReturn(List.of("safe result"));

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of("private trace context probe query ownerToken=secret"));

        assertFalse(TraceContext.isAttached());

        service.run(request);

        assertFalse(TraceContext.isAttached());
    }

    @Test
    void runSampleProviderExceptionUsesStableErrorWithoutClassNameOrRawQuery() throws Exception {
        String rawQuery = "private soak provider failure ownerToken=secret";
        HybridWebSearchProvider hybrid = mock(HybridWebSearchProvider.class);
        when(hybrid.search(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("provider failed query=" + rawQuery));

        WebSoakKpiProbeService service = new WebSoakKpiProbeService(
                hybrid,
                new MockEnvironment(),
                objectMapper);
        WebSoakKpiProbeController.Request request = new WebSoakKpiProbeController.Request();
        request.setIterations(1);
        request.setQueries(List.of(rawQuery));

        WebSoakKpiProbeService.Report report = service.run(request);

        assertEquals(1, report.getSamples().size());
        String error = report.getSamples().get(0).getError();
        assertTrue(error.startsWith("provider_error#"), error);
        assertEquals("provider_error#".length() + 12, error.length(), error);
        Map<String, Object> sample = objectMapper.convertValue(
                report.getSamples().get(0),
                new TypeReference<>() {
                });
        Map<?, ?> kpi = assertInstanceOf(Map.class, sample.get("kpi"));
        assertEquals(Boolean.TRUE, kpi.get("probe.websoakKpi.runOnce.failed"));
        assertEquals("provider_error", kpi.get("probe.websoakKpi.runOnce.failureType"));
        assertEquals(SafeRedactor.hashValue("provider failed query=" + rawQuery),
                kpi.get("probe.websoakKpi.runOnce.messageHash"));
        assertEquals(("provider failed query=" + rawQuery).length(),
                ((Number) kpi.get("probe.websoakKpi.runOnce.messageLength")).longValue());

        String serialized = objectMapper.writeValueAsString(report);
        assertFalse(serialized.contains(rawQuery));
        assertFalse(serialized.contains("IllegalStateException"), serialized);
        assertFalse(serialized.contains("ownerToken=secret"), serialized);
    }

    @Test
    void probeLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeService.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("baseline file not found: {}"));
        assertTrue(source.contains("baseline file not found pathHash={} pathLength={}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains("baseline parse failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("log.debug(\"[WebSoakKPI] sleep interrupted stage={}\", \"service.run.sleepBetween\")"));
        assertTrue(source.contains("baseline json line skipped lineHash={} lineLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(ln), ln == null ? 0 : ln.length()"));
    }

    @Test
    void numericKpiProbeParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeService.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertParserCatchNarrowed(source, "return Long.parseLong(s.trim());");
        assertParserCatchNarrowed(source, "return Long.parseLong(String.valueOf(v).trim());");
        assertNumericFallbackLog(source, "return Long.parseLong(s.trim());", "service.baseline.parseLongSafe");
        assertNumericFallbackLog(source, "return Long.parseLong(String.valueOf(v).trim());", "service.safeLong");
        assertFalse(source.contains("log.debug(\"[WebSoakKPI] numeric parse fallback stage={} value={}"));
    }

    private static void assertParserCatchNarrowed(String source, String parserCall) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "WebSoak KPI numeric parser fallbacks must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "WebSoak KPI numeric parser fallbacks should catch only NumberFormatException");
    }

    private static void assertNumericFallbackLog(String source, String parserCall, String stage) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 320));
        assertTrue(window.contains("numeric parse fallback stage={} errorType={}"),
                "numeric fallback should include stable error type in log format: " + stage);
        assertTrue(window.contains("\"" + stage + "\""),
                "numeric fallback should include stage label: " + stage);
        assertTrue(window.contains("\"invalid_number\""),
                "numeric fallback should classify parse failure as invalid_number: " + stage);
    }
}
