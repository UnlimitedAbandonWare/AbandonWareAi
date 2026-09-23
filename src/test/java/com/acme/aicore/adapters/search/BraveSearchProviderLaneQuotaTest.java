package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.example.lms.infra.upstash.UpstashBackedWebCache;
import com.example.lms.infra.upstash.UpstashRateLimiter;
import com.example.lms.search.TraceStore;
import com.example.lms.service.web.BraveSearchProperties;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boundary defect: the provider pinned a BASE token but still ran the
 * unconditional FREE-tier quota reservation, so an exhausted FREE lane denied
 * the request before any HTTP call even though BASE was selected.
 * (Conditional path: this provider is only scanned when
 * adapter.acme-websearch.enabled=true.)
 */
class BraveSearchProviderLaneQuotaTest {

    private static final String BASE_URL = "https://api.search.brave.com";
    private static final String FREE = "brave-free-lane-test-token";
    private static final String BASE = "brave-base-lane-test-token";
    private static final String BODY =
            "{\"web\":{\"results\":[{\"title\":\"t\",\"description\":\"d\",\"url\":\"https://example.com/r\"}]}}";

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void exhaustedFreeLaneStillReachesHttpWithBaseToken() {
        AtomicReference<String> wireToken = new AtomicReference<>();
        BraveSearchProvider provider = provider(capturingWebClient(wireToken), BASE);
        BraveSearchService quotaGuard = quotaGuard(FREE, BASE, 0);
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery("base survives free exhaustion"))
                .block(Duration.ofSeconds(3));

        assertEquals(BASE, wireToken.get());
        assertEquals(1, bundle.docs().size());
    }

    @Test
    void freeActiveLaneReservesAndSendsFreeToken() {
        AtomicReference<String> wireToken = new AtomicReference<>();
        BraveSearchProvider provider = provider(capturingWebClient(wireToken), BASE);
        BraveSearchService quotaGuard = quotaGuard(FREE, BASE, 3);
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery("free lane reserved"))
                .block(Duration.ofSeconds(3));

        assertEquals(FREE, wireToken.get());
        assertEquals(1, bundle.docs().size());
        assertEquals(2, quotaGuard.monthlyRemaining().get());
    }

    @Test
    void singleKeyManagedQuotaStillAppliesToBaseLane() {
        // With no FREE key configured the monthly quota still governs: the
        // reservation is consumed and the wire token stays BASE.
        AtomicReference<String> wireToken = new AtomicReference<>();
        BraveSearchProvider provider = provider(capturingWebClient(wireToken), BASE);
        BraveSearchService quotaGuard = quotaGuard("", BASE, 2);
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery("single key managed quota"))
                .block(Duration.ofSeconds(3));

        assertEquals(BASE, wireToken.get());
        assertEquals(1, bundle.docs().size());
        assertEquals(1, quotaGuard.monthlyRemaining().get());
    }

    @Test
    void deniedFreeReservationWithoutBaseStillSkipsHttp() {
        // FREE exhausted with no BASE key: the guard is operationally disabled,
        // so no HTTP call may happen (counterexample to unconditional BASE).
        AtomicReference<String> wireToken = new AtomicReference<>();
        BraveSearchProvider provider = provider(capturingWebClient(wireToken), "");
        BraveSearchService quotaGuard = quotaGuard(FREE, "", 0);
        ReflectionTestUtils.setField(provider, "braveQuotaGuard", quotaGuard);

        SearchBundle bundle = provider.search(new WebSearchQuery("no key anywhere"))
                .block(Duration.ofSeconds(3));

        assertNull(wireToken.get());
        assertTrueEmpty(bundle);
    }

    private static void assertTrueEmpty(SearchBundle bundle) {
        assertEquals(0, bundle == null || bundle.docs() == null ? 0 : bundle.docs().size());
    }

    private static WebClient.Builder capturingWebClient(AtomicReference<String> wireToken) {
        return WebClient.builder().exchangeFunction(request -> {
            wireToken.set(request.headers().getFirst("X-Subscription-Token"));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(BODY)
                    .build());
        });
    }

    private static BraveSearchService quotaGuard(String free, String base, int remaining) {
        BraveSearchService guard = new BraveSearchService(new BraveSearchProperties(
                true, BASE_URL + "/res/v1/web/search", base, 1.0d, 10, 500L, 200L, 2000L));
        ReflectionTestUtils.setField(guard, "configEnabled", true);
        ReflectionTestUtils.setField(guard, "apiKey", base);
        ReflectionTestUtils.setField(guard, "apiKeyFree", free);
        ReflectionTestUtils.setField(guard, "baseUrl", BASE_URL + "/res/v1/web/search");
        ReflectionTestUtils.setField(guard, "timeoutMs", 2000);
        ReflectionTestUtils.setField(guard, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(guard, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.invokeMethod(guard, "init");
        guard.monthlyRemaining().set(remaining);
        if (remaining <= 0) {
            ReflectionTestUtils.setField(guard, "quotaExhausted", true);
        }
        return guard;
    }

    private static BraveSearchProvider provider(WebClient.Builder webClient, String apiKey) {
        UpstashBackedWebCache cache = mock(UpstashBackedWebCache.class);
        when(cache.get(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cache.put(anyString(), anyString(), nullable(Duration.class))).thenReturn(Mono.empty());

        UpstashRateLimiter limiter = mock(UpstashRateLimiter.class);
        when(limiter.allow(anyString(), anyLong(), any(Duration.class))).thenReturn(Mono.just(true));

        BraveSearchProvider provider = new BraveSearchProvider(webClient, cache, limiter);
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "enabled", true);
        ReflectionTestUtils.setField(provider, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(provider, "count", 3);
        ReflectionTestUtils.setField(provider, "timeoutMs", 2000);
        ReflectionTestUtils.setField(provider, "timeoutSec", 2);
        ReflectionTestUtils.setField(provider, "qps", 1);
        return provider;
    }
}
