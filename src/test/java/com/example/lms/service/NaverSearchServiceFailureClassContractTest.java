package com.example.lms.service;

import com.example.lms.guard.GuardProfile;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.impl.NaverProvider;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaverSearchServiceFailureClassContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"none", "rrf"})
    void unavailableOptionalSessionSupplierDoesNotDiscardRetrievedPublicEvidence(String fusion) {
        AtomicInteger attempts = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"items\":[{\"title\":\"Synthetic retrieval evidence\","
                            + "\"link\":\"https://example.org/rag\",\"description\":\"Synthetic retrieval evidence\"}]}")
                    .build());
        }).build();
        var service = naverService(client, threeVariants());
        ReflectionTestUtils.setField(service, "fusionPolicy", fusion);
        Supplier<Long> unavailableSession = () -> {
            throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                    org.springframework.core.ResolvableType.forClassWithGenerics(Supplier.class, Long.class));
        };
        ReflectionTestUtils.setField(service, "sessionIdProvider", unavailableSession);
        TraceStore.put("web.boundedRoute", true);

        var result = service.searchWithTraceSync("synthetic retrieval release notes", 3);

        assertEquals(1, attempts.get(), "the public provider result was actually received");
        assertEquals(1, result.snippets().size(), "optional session reinforcement must preserve retrieved evidence");
        assertTrue(result.snippets().get(0).contains("https://example.org/rag"));
        assertEquals("NONE", result.trace().outcomeClass);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "rrf"})
    void availableSessionStillReinforcesRetrievedEvidence(String fusion) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"items\":[{\"title\":\"Synthetic evidence\","
                                + "\"link\":\"https://example.org/rag\",\"description\":\"Synthetic evidence\"}]}")
                        .build())).build();
        var service = naverService(client, threeVariants());
        var memory = mock(MemoryReinforcementService.class);
        ReflectionTestUtils.setField(service, "memorySvc", memory);
        ReflectionTestUtils.setField(service, "sessionIdProvider", (Supplier<Long>) () -> 7L);
        ReflectionTestUtils.setField(service, "fusionPolicy", fusion);
        TraceStore.put("web.boundedRoute", true);

        var result = service.searchWithTraceSync("synthetic retrieval release notes", 3);

        assertEquals(1, result.snippets().size());
        verify(memory, timeout(2000).times(1)).reinforceWithSnippet(
                eq("7"), anyString(), eq(result.snippets().get(0)), eq("WEB"), anyDouble());
    }

    @Test
    void normalProviderKeepsSourceUrlAndStripsDisplayMarkup() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"items\":[{\"title\":\"<b>Synthetic</b> evidence\","
                                + "\"link\":\"https://example.org/rag\",\"description\":\"<i>Public</i> evidence\"}]}")
                        .build())).build();
        var service = naverService(client, threeVariants());
        // A manually constructed fixture does not receive the @Value default.
        ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 3000L);
        TraceStore.put("web.boundedRoute", true);

        var result = new NaverProvider(service).search(
                new WebSearchQuery("synthetic retrieval evidence", 3, null, null));

        assertEquals(1, result.getDocuments().size());
        var document = result.getDocuments().get(0);
        assertEquals("https://example.org/rag", document.getUrl());
        assertEquals("Synthetic evidence", document.getTitle());
        assertTrue(document.getSnippet().contains("Public evidence"));
        assertFalse(document.getSnippet().contains("<"));
    }

    @Test
    void malformedResponseKeepsParseFailureAtExistingBoundary() {
        WebClient client=WebClient.builder().exchangeFunction(request->Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type","application/json").body("{malformed-synthetic}").build())).build();
        var service=naverService(client,threeVariants());
        TraceStore.put("web.boundedRoute",true);
        assertTrue(service.searchWithTraceSync("synthetic parser probe",3).snippets().isEmpty());
        assertEquals("PARSE_ERROR",TraceStore.get("web.naver.failureClass"));
    }

    @Test
    void resultTraceCarriesItsOwnOutcomeWithoutReadingStaleScalarState() {
        var statuses=List.of(HttpStatus.UNAUTHORIZED,HttpStatus.TOO_MANY_REQUESTS,HttpStatus.GATEWAY_TIMEOUT,HttpStatus.OK,HttpStatus.OK);
        var bodies=List.of("{}","{}","{}","{malformed-synthetic}","{\"items\":[]}");
        var expected=List.of("AUTH_OR_CONFIG","RATE_LIMIT","TIMEOUT_OR_BUDGET","PARSE_ERROR","TRUE_ZERO");
        for(int i=0;i<statuses.size();i++){
            var status=statuses.get(i);var body=bodies.get(i);var attempts=new AtomicInteger();
            WebClient client=WebClient.builder().exchangeFunction(request->{attempts.incrementAndGet();return Mono.just(ClientResponse.create(status)
                    .header("Content-Type","application/json").body(body).build());}).build();
            var service=naverService(client,threeVariants());
            TraceStore.put("web.boundedRoute",true);TraceStore.put("web.naver.failureClass","STALE_SENTINEL");
            var result=service.searchWithTraceSync("synthetic classified query "+i,3);
            assertTrue(result.snippets().isEmpty());assertEquals(1,attempts.get());
            assertEquals(expected.get(i),ReflectionTestUtils.getField(result.trace(),"outcomeClass"));
        }
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void unauthorizedStopsVariantFanoutAndKeepsUnobservedCountsUnknown() {
        assertAuthFailureStopsVariantFanout(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void forbiddenStopsVariantFanoutAndKeepsUnobservedCountsUnknown() {
        assertAuthFailureStopsVariantFanout(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingCredentialsStopBeforeRewriteOrProviderAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    throw new AssertionError("missing credentials must not call Naver");
                })
                .build();
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transform(anyString(), anyString()))
                .thenThrow(new AssertionError("missing credentials must not rewrite the query"));
        NaverSearchService service = naverService(webClient, transformer, "");

        TraceStore.put("web.naver.failureClass", "PROVIDER_ERROR");
        TraceStore.put("web.naver.providerAttemptObserved", true);
        TraceStore.put("web.naver.providerResultCount", 99);
        TraceStore.put("web.naver.preFilterCount", 99);
        TraceStore.put("web.naver.postFilterCount", 99);
        TraceStore.put("web.naver.mergeCount", 99);

        List<String> out = service.searchSnippetsMono("same input missing credentials probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(0, attempts.get());
        assertEquals("AUTH_OR_CONFIG", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerAttemptObserved"));
        assertUnknownCounts();
    }

    @Test
    void firstTrueZeroThenUnauthorizedStopsRemainingVariants() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    int attempt = attempts.incrementAndGet();
                    HttpStatus status = attempt == 1 ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
                    String body = attempt == 1 ? "{\"items\":[]}" : "{\"error\":\"invalid-client\"}";
                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .build());
                })
                .build();
        NaverSearchService service = naverService(webClient, threeVariants());

        List<String> out = service.searchSnippetsMono("same input auth probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(2, attempts.get(), "AUTH_OR_CONFIG after TRUE_ZERO must stop not-yet-started variants");
        assertEquals("AUTH_OR_CONFIG", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerAttemptObserved"));
        assertUnknownCounts();
    }

    @Test
    void rateLimitDoesNotReleaseVariants() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                            .header("Content-Type", "application/json")
                            .body("{\"error\":\"rate-limited\"}")
                            .build());
                })
                .build();
        NaverSearchService service = naverService(webClient, threeVariants());

        List<String> out = service.searchSnippetsMono("same input rate-limit probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(1, attempts.get(), "RATE_LIMIT must not be treated as TRUE_ZERO");
        assertEquals("RATE_LIMIT", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerAttemptObserved"));
        assertUnknownCounts();
    }

    @Test
    void trueZeroMayReleaseOneBoundedVariant() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    int attempt = attempts.incrementAndGet();
                    String body = attempt == 1
                            ? "{\"items\":[]}"
                            : "{\"items\":[{\"title\":\"result\",\"link\":\"https://example.com/a\",\"description\":\"usable\"}]}";
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .build());
                })
                .build();
        NaverSearchService service = naverService(webClient, threeVariants());

        List<String> out = service.searchSnippetsMono("same input true-zero probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(!out.isEmpty());
        assertEquals(2, attempts.get(), "TRUE_ZERO may release a bounded next variant");
    }

    @Test
    void boundedHybridRouteSkipsQueryTransformerAdaptiveVariantsAndHttpRetry() {
        TraceStore.put("web.boundedRoute", true);
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", "application/json")
                            .body("{\"error\":\"temporary\"}")
                            .build());
                })
                .build();
        QueryTransformer transformer = threeVariants();
        NaverSearchService service = naverService(webClient, transformer);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", true);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);

        List<String> out = service.searchSnippetsMono("bounded naver query", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(1, attempts.get());
        verify(transformer, never()).transform(anyString(), anyString());
        assertEquals("bounded-route", TraceStore.get("web.naver.adaptive.triggerReason"));
        assertEquals(0L, TraceStore.getLong("web.naver.retry.count"));
    }

    @Test
    void trueZeroIsNotReusedFromCacheAndKeepsObservedLineage() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"items\":[]}")
                            .build());
                })
                .build();
        NaverSearchService service = naverService(webClient, threeVariants());

        List<String> first = service.searchSnippetsMono("same input true-zero cache probe", 3)
                .block(Duration.ofSeconds(5));
        assertNotNull(first);
        assertTrue(first.isEmpty());
        int attemptsAfterFirst = attempts.get();
        assertTrue(attemptsAfterFirst > 0);

        TraceStore.clear();
        List<String> second = service.searchSnippetsMono("same input true-zero cache probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(second);
        assertTrue(second.isEmpty());
        assertTrue(attempts.get() > attemptsAfterFirst,
                "TRUE_ZERO must not be reused from a provenance-free empty cache entry");
        assertEquals("TRUE_ZERO", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerAttemptObserved"));
        assertEquals(0, TraceStore.get("web.naver.providerResultCount"));
        assertEquals(0, TraceStore.get("web.naver.preFilterCount"));
        assertEquals(0, TraceStore.get("web.naver.postFilterCount"));
        assertEquals("unknown", TraceStore.get("web.naver.mergeCount"));
    }

    private static void assertAuthFailureStopsVariantFanout(HttpStatus status) {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", "application/json")
                            .body("{\"error\":\"invalid-client\"}")
                            .build());
                })
                .build();
        NaverSearchService service = naverService(webClient, threeVariants());

        List<String> out = service.searchSnippetsMono("same input auth probe", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(1, attempts.get(), "401/403 must stop later query variants and retries");
        assertEquals("AUTH_OR_CONFIG", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerAttemptObserved"));
        assertUnknownCounts();
    }

    private static void assertUnknownCounts() {
        assertEquals("unknown", TraceStore.get("web.naver.providerResultCount"));
        assertEquals("unknown", TraceStore.get("web.naver.preFilterCount"));
        assertEquals("unknown", TraceStore.get("web.naver.postFilterCount"));
        assertEquals("unknown", TraceStore.get("web.naver.mergeCount"));
    }

    private static QueryTransformer threeVariants() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transform(anyString(), anyString())).thenReturn(List.of(
                "first auth variant",
                "second auth variant",
                "third auth variant"));
        return transformer;
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naverService(WebClient webClient, QueryTransformer transformer) {
        return naverService(webClient, transformer, "test-client:test-secret");
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naverService(WebClient webClient,
                                                   QueryTransformer transformer,
                                                   String naverKeys) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        when(ratePolicy.allowedExpansions()).thenReturn(3);
        when(ratePolicy.currentDelayMs()).thenReturn(0L);

        GuardProfileProps guardProfileProps = mock(GuardProfileProps.class);
        when(guardProfileProps.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);

        NaverSearchService service = new NaverSearchService(
                transformer,
                mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class),
                mock(EmbeddingStore.class),
                mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null,
                null,
                naverKeys,
                "",
                "",
                16L,
                30L,
                mock(PlatformTransactionManager.class),
                ratePolicy,
                webClient,
                mock(ObjectProvider.class),
                null);
        ReflectionTestUtils.setField(service, "guardProfileProps", guardProfileProps);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "similarThreshold", 0.99d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        return service;
    }
}
