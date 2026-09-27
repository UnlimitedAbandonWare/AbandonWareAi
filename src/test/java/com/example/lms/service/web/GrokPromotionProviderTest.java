package com.example.lms.service.web;

import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GrokPromotionProviderTest {
    private static final String QUERY = "SuperGrok Heavy discount existing account Google Play";

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @Test
    void braveFullListPriceResultDoesNotStopAccountOfferDiscovery() {
        BraveSearchService service = brave();
        RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        List<String> queries = new ArrayList<>();
        server.expect(request -> queries.add(decoded(request.getURI().getRawQuery())))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(braveBody("list-price")));
        server.expect(request -> queries.add(decoded(request.getURI().getRawQuery())))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(braveBody("account-offer")));

        BraveSearchResult result = service.searchWithMeta(QUERY, 1);

        assertTrue(result.snippets().stream().anyMatch(s -> s.contains("account-offer")));
        assertEquals(1, result.snippets().size());
        assertEquals(2, queries.size());
        assertTrue(queries.get(1).contains("retention"));
        assertTrue(queries.stream().allMatch(q -> q.contains("Heavy") && q.contains("Google Play")));
        assertEquals("base_results_not_exhaustive", TraceStore.get("web.brave.promotionDiscovery.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(QUERY));
        server.verify();
    }

    @Test
    void braveOrdinaryResultsAndBoundedRouteStillMakeOnlyOneAttempt() {
        for (boolean bounded : List.of(false, true)) {
            TraceStore.clear();
            if (bounded) TraceStore.put("web.boundedRoute", true);
            BraveSearchService service = brave();
            RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
            MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
            server.expect(request -> { }).andRespond(withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON).body(braveBody("list-price")));
            assertFalse(service.searchWithMeta(bounded ? QUERY : "Java reference", 1).snippets().isEmpty());
            server.verify();
        }
    }

    @Test
    void braveAuthenticationFailureIsNotAReasonToTryPromotionVariants() {
        BraveSearchService service = brave();
        RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(request -> { }).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertEquals(BraveSearchResult.Status.HTTP_ERROR, service.searchWithMeta(QUERY, 1).status());
        server.verify();
    }

    @Test
    void naverFullListPriceResultDoesNotCancelSubscriptionToOfferQueries() {
        List<String> queries = new ArrayList<>();
        NaverSearchService service = naver(WebClient.builder().exchangeFunction(request -> {
            queries.add(decoded(request.url().getRawQuery()));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(naverBody(queries.size() == 1 ? "list-price" : "account-offer")).build());
        }).build());

        List<String> snippets = collect(service, QUERY, false);

        assertTrue(snippets.stream().anyMatch(s -> s.contains("account-offer")), String.valueOf(snippets));
        assertEquals(1, snippets.size());
        assertEquals(2, queries.size());
        assertTrue(queries.get(1).contains("retention"));
        assertTrue(queries.stream().map(q -> q.toLowerCase(java.util.Locale.ROOT))
                .allMatch(q -> q.contains("heavy") && q.contains("google play")));
        assertEquals("base_results_not_exhaustive", TraceStore.get("web.naver.promotionDiscovery.reason"));
    }

    @Test
    void naverOrdinaryAndExplicitlyBoundedRequestsKeepOneAttempt() {
        for (boolean bounded : List.of(false, true)) {
            AtomicInteger attempts = new AtomicInteger();
            NaverSearchService service = naver(WebClient.builder().exchangeFunction(request -> {
                attempts.incrementAndGet();
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(naverBody("list-price")).build());
            }).build());
            assertFalse(collect(service, bounded ? QUERY : "Java reference", bounded).isEmpty());
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void naverFailureStopsDiscoveryAndRetainsAlreadyObservedEvidence() {
        AtomicInteger attempts = new AtomicInteger();
        NaverSearchService service = naver(WebClient.builder().exchangeFunction(request -> {
            int n = attempts.incrementAndGet();
            return Mono.just(n == 1
                    ? ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(naverBody("list-price")).build()
                    : ClientResponse.create(HttpStatus.FORBIDDEN).body("{}").build());
        }).build());
        List<String> result = collect(service, QUERY, false);
        assertEquals(2, attempts.get());
        assertTrue(result.stream().anyMatch(s -> s.contains("list-price")));
    }

    @SuppressWarnings("unchecked")
    private List<String> collect(NaverSearchService service, String query, boolean bounded) {
        Flux<String> result = ReflectionTestUtils.invokeMethod(service, "collectNaverAdaptive",
                List.of(query), NaverSearchService.SearchPolicy.freeMode(), 1, 10, query,
                4500L, 2000L, TraceStore.context(), bounded);
        assertNotNull(result);
        return result.collectList().block(Duration.ofSeconds(6));
    }

    private BraveSearchService brave() {
        String key = "fixture-promotion-client";
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(true,
                "https://api.search.brave.com/res/v1/web/search", key, 100.0d, 2000, 500L, 200L, 2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", key);
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.search.brave.com/res/v1/web/search");
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveMaxQueries", 2);
        service.init();
        return service;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverSearchService naver(WebClient client) {
        NaverSearchService service = new NaverSearchService(null, null, null,
                mock(dev.langchain4j.store.embedding.EmbeddingStore.class),
                mock(dev.langchain4j.model.embedding.EmbeddingModel.class), () -> 1L, null,
                "", "fixture-promotion-client", "fixture-promotion-value", 10L, 1L,
                mock(PlatformTransactionManager.class), new RateLimitPolicy(), client, null, null);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "display", 10);
        ReflectionTestUtils.setField(service, "webTopK", 3);
        ReflectionTestUtils.setField(service, "guardProfileProps", mock(com.example.lms.guard.GuardProfileProps.class));
        ReflectionTestUtils.setField(service, "adaptiveMaxQueries", 2);
        // Direct construction does not run Spring's keyword-set initialization.
        ReflectionTestUtils.setField(service, "productKeywords", java.util.Set.of());
        ReflectionTestUtils.setField(service, "foldKeywords", java.util.Set.of());
        ReflectionTestUtils.setField(service, "flipKeywords", java.util.Set.of());
        return service;
    }

    private String decoded(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private String braveBody(String label) {
        return "{\"web\":{\"results\":[{\"title\":\"" + label + "\",\"description\":\"Observed subscription terms for this offer\",\"url\":\"https://example.com/" + label + "\"}]}}";
    }
    private String naverBody(String label) {
        return "{\"total\":1,\"items\":[{\"title\":\"" + label + "\",\"description\":\"Observed subscription terms for this offer\",\"link\":\"https://example.com/" + label + "\"}]}";
    }
}
