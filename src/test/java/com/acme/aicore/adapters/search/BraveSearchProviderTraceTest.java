package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.example.lms.infra.upstash.UpstashBackedWebCache;
import com.example.lms.infra.upstash.UpstashRateLimiter;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BraveSearchProviderTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void missingApiKeyEmitsCanonicalDisabledTraceWithoutExternalCall() {
        TraceStore.clear();
        BraveSearchProvider provider = provider(WebClient.builder(), "", true);

        SearchBundle bundle = provider.search(new WebSearchQuery("private brave disabled query"))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals(3, TraceStore.get("web.brave.requestedCount"));
        assertEquals(0, TraceStore.get("web.brave.returnedCount"));
        assertEquals(0, TraceStore.get("web.brave.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.afterFilterStarved"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReason"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.brave.failureReason"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.queryHash")).startsWith("hash:"));
        assertTraceDoesNotContain("private brave disabled query", "sk-" + "brave-disabled-secret");
    }

    @Test
    void disabledTraceClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.brave.timeout", true);
        TraceStore.put("web.brave.cancelled", true);
        TraceStore.put("web.brave.httpStatus", 429);
        TraceStore.put("web.brave.429", true);
        TraceStore.put("web.brave.rateLimited", true);
        BraveSearchProvider provider = provider(WebClient.builder(), "", true);

        SearchBundle bundle = provider.search(new WebSearchQuery("private brave disabled residue query"))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.cancelled"));
        assertEquals(null, TraceStore.get("web.brave.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.rateLimited"));
        assertEquals("provider-disabled", TraceStore.get("web.brave.failureReason"));
        assertTraceDoesNotContain("private brave disabled residue query", "sk-" + "brave-disabled-secret");
    }

    @Test
    void http429EmitsRateLimitTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String rawQuery = "private brave raw query";
        String apiKey = "sk-" + "brave-secret-000";
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"rate limit for " + rawQuery + " token=" + apiKey + "\"}")
                        .build()));
        BraveSearchProvider provider = provider(webClient, apiKey, true);

        SearchBundle bundle = provider.search(new WebSearchQuery(rawQuery))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(429, TraceStore.get("web.brave.httpStatus"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.429"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.rateLimited"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.rateLimited"));
        assertEquals("rate-limit", TraceStore.get("web.brave.failureReason"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.errorBodyHash")).startsWith("hash:"));
        assertTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void timeoutEmitsTimeoutTraceWithoutRawQuery() {
        TraceStore.clear();
        String rawQuery = "private brave timeout query";
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new TimeoutException("timed out for " + rawQuery)));
        String apiKey = "sk-" + "brave-secret-001";
        BraveSearchProvider provider = provider(webClient, apiKey, true);

        SearchBundle bundle = provider.search(new WebSearchQuery(rawQuery))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.timeout"));
        assertEquals("timeout", TraceStore.get("web.brave.failureReason"));
        assertTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void cancellationEmitsCancelledTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String rawQuery = "private brave cancelled query";
        String apiKey = "sk-" + "brave-secret-004";
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new CancellationException("cancelled ownerToken=secret query=" + rawQuery)));
        BraveSearchProvider provider = provider(webClient, apiKey, true);

        SearchBundle bundle = provider.search(new WebSearchQuery(rawQuery))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.brave.exceptionType"));
        assertEquals("cancelled", TraceStore.get("web.brave.failureReason"));
        assertTraceDoesNotContain(rawQuery, apiKey);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void exhaustedSharedQuotaSkipsWebClientCall() {
        TraceStore.clear();
        String rawQuery = "private brave quota query";
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("Brave WebClient must not run when shared quota is exhausted");
                });
        String apiKey = "sk-" + "brave-secret-002";
        BraveSearchProvider provider = provider(webClient, apiKey, true);
        com.example.lms.service.web.BraveSearchService quotaGuard = new com.example.lms.service.web.BraveSearchService(
                new com.example.lms.service.web.BraveSearchProperties(
                        true,
                        "https://api.search.brave.com/res/v1/web/search",
                        apiKey,
                        1.0d,
                        1,
                        500L,
                        200L,
                        2000L));
        ReflectionTestUtils.setField(quotaGuard, "configEnabled", true);
        ReflectionTestUtils.setField(quotaGuard, "apiKey", apiKey);
        ReflectionTestUtils.invokeMethod(quotaGuard, "init");
        quotaGuard.markQuotaExhausted();
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery(rawQuery))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals("quota_exhausted", TraceStore.get("web.brave.disabledReason"));
        assertEquals("quota_exhausted", TraceStore.get("web.brave.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.brave.failureReason"));
        assertTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void sharedQuotaDisabledReasonIsRedactedBeforeTraceStore() {
        TraceStore.clear();
        String rawQuery = "private brave quota reason query";
        String rawReasonSecret = "sk-" + "brave-disabled-reason-secret-1234567890";
        WebClient.Builder webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("Brave WebClient must not run when shared quota is disabled");
                });
        String apiKey = "sk-" + "brave-secret-003";
        BraveSearchProvider provider = provider(webClient, apiKey, true);
        com.example.lms.service.web.BraveSearchService quotaGuard = new com.example.lms.service.web.BraveSearchService(
                new com.example.lms.service.web.BraveSearchProperties(
                        true,
                        "https://api.search.brave.com/res/v1/web/search",
                        apiKey,
                        1.0d,
                        1,
                        500L,
                        200L,
                        2000L));
        ReflectionTestUtils.setField(quotaGuard, "configEnabled", true);
        ReflectionTestUtils.setField(quotaGuard, "apiKey", apiKey);
        ReflectionTestUtils.invokeMethod(quotaGuard, "init");
        quotaGuard.setOperationallyDisabled("quota_exhausted api_key=" + rawReasonSecret);
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery(rawQuery))
                .block(Duration.ofSeconds(1));

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals("provider-disabled", TraceStore.get("web.brave.failureReason"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.disabledReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.skipped.reason")).startsWith("hash:"));
        assertTraceDoesNotContain(rawQuery, rawReasonSecret);
    }

    @Test
    void failureLoggingDoesNotRenderThrowableToString() throws Exception {
        String source = Files.readString(Path.of("main/java/com/acme/aicore/adapters/search/BraveSearchProvider.java"));

        assertFalse(source.contains("ex.toString()"));
        assertTrue(source.contains("failureReason(ex)"));
        assertTrue(source.contains("SafeRedactor.hashValue(query)"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
    }

    @Test
    void telemetryCatchBlocksUseNamedSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of("main/java/com/acme/aicore/adapters/search/BraveSearchProvider.java"));

        assertTrue(source.contains("traceSuppressed(\"countsTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"failureTrace\", ignore);"));
        assertTrue(source.contains("private static void traceSuppressed(String stage, Throwable failure)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.provider.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.provider.suppressed.errorType\", errorType);"));
    }

    private static BraveSearchProvider provider(WebClient.Builder webClient, String apiKey, boolean enabled) {
        UpstashBackedWebCache cache = mock(UpstashBackedWebCache.class);
        when(cache.get(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cache.put(anyString(), anyString(), nullable(Duration.class))).thenReturn(Mono.empty());

        UpstashRateLimiter limiter = mock(UpstashRateLimiter.class);
        when(limiter.allow(anyString(), anyLong(), any(Duration.class))).thenReturn(Mono.just(true));

        BraveSearchProvider provider = new BraveSearchProvider(webClient, cache, limiter);
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "enabled", enabled);
        ReflectionTestUtils.setField(provider, "baseUrl", "https://api.search.brave.com");
        ReflectionTestUtils.setField(provider, "count", 3);
        ReflectionTestUtils.setField(provider, "timeoutMs", 2000);
        ReflectionTestUtils.setField(provider, "timeoutSec", 2);
        ReflectionTestUtils.setField(provider, "qps", 1);
        return provider;
    }

    private static void assertTraceDoesNotContain(String rawQuery, String secret) {
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(secret), trace);
    }
}
