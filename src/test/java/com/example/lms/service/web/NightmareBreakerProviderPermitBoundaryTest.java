package com.example.lms.service.web;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NightmareBreakerProviderPermitBoundaryTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void creatingNaverMonoConsumesNoAdmissionAndSubscriptionConsumesExactlyOne() {
        CountingBreaker breaker = new CountingBreaker();
        NaverSearchService service = naverService(
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(jsonResponse("{\"items\":[]}")))
                        .build(),
                breaker);

        Mono<List<String>> search = naverProviderMono(service, "naver-permit-probe");

        assertEquals(0, breaker.acquireCalls.get(), "Mono assembly must not consume a permit");

        List<String> result = search.block(Duration.ofSeconds(2));

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(1, breaker.acquireCalls.get(), "one subscription owns one wire permit");
    }

    @Test
    void malformedNaverResponseDoesNotRecordSuccess() {
        CountingBreaker breaker = new CountingBreaker();
        breaker.signalFailure(NightmareKeys.WEBSEARCH_NAVER,
                NightmareBreaker.FailureKind.UNKNOWN, null, "seed");
        NaverSearchService service = naverService(
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(jsonResponse("{not-json")))
                        .build(),
                breaker);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> naverProviderMono(service, "naver-malformed-probe").block(Duration.ofSeconds(2)));

        assertTrue(String.valueOf(failure.getMessage()).contains("PROVIDER_ERROR"));
        NightmareBreaker.StateView state = breaker.inspect(NightmareKeys.WEBSEARCH_NAVER);
        assertEquals(1, breaker.acquireCalls.get());
        assertEquals(0, state.consecutiveSuccesses);
        assertTrue(state.consecutiveFailures >= 1,
                "malformed JSON must not clear the pre-existing adverse state as success would");
    }

    @Test
    void blankNaverResponseDoesNotRecordSuccess() {
        CountingBreaker breaker = new CountingBreaker();
        breaker.signalFailure(NightmareKeys.WEBSEARCH_NAVER,
                NightmareBreaker.FailureKind.UNKNOWN, null, "seed");
        NaverSearchService service = naverService(
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(jsonResponse("")))
                        .build(),
                breaker);

        assertThrows(RuntimeException.class,
                () -> naverProviderMono(service, "naver-blank-probe").block(Duration.ofSeconds(2)));

        NightmareBreaker.StateView state = breaker.inspect(NightmareKeys.WEBSEARCH_NAVER);
        assertEquals(1, breaker.acquireCalls.get());
        assertEquals(0, state.consecutiveSuccesses);
        assertEquals(1, state.consecutiveFailures,
                "blank completion must not clear the pre-existing adverse state");
        assertEquals(1, state.consecutiveBlanks, "blank body must use the blank terminal");
    }

    @Test
    void braveDisabledGuardConsumesNoPermit() {
        CountingBreaker breaker = new CountingBreaker();
        BraveSearchService service = braveService("", breaker);

        BraveSearchResult result = service.searchWithMeta("brave-disabled-probe", 3);

        assertEquals(BraveSearchResult.Status.DISABLED, result.status());
        assertEquals(0, breaker.acquireCalls.get(), "local disabled guard must run before admission");
    }

    @Test
    void braveValidZeroResultJsonRecordsSuccess() {
        CountingBreaker breaker = new CountingBreaker();
        breaker.signalFailure(NightmareKeys.WEBSEARCH_BRAVE,
                NightmareBreaker.FailureKind.UNKNOWN, null, "seed");
        assertEquals(1, breaker.inspect(NightmareKeys.WEBSEARCH_BRAVE).consecutiveFailures);

        BraveSearchService service = braveService("brave-unit-value-123456", breaker);
        org.springframework.web.client.RestTemplate restTemplate =
                (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withSuccess("{\"web\":{\"results\":[]}}", MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("brave-valid-zero-probe", 3);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertTrue(result.snippets().isEmpty());
        assertEquals(1, breaker.acquireCalls.get());
        assertEquals(0, breaker.inspect(NightmareKeys.WEBSEARCH_BRAVE).consecutiveFailures,
                "valid zero-result JSON must complete the permit successfully");
    }

    private static ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverService(WebClient webClient, NightmareBreaker breaker) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        NaverSearchService service = new NaverSearchService(
                null,
                mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class),
                mock(EmbeddingStore.class),
                mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null,
                null,
                "naver-unit-id:naver-unit-value-123456",
                "",
                "",
                16L,
                30L,
                mock(PlatformTransactionManager.class),
                ratePolicy,
                webClient,
                mock(ObjectProvider.class),
                null);
        ReflectionTestUtils.setField(service, "nightmareBreaker", breaker);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 0);
        ReflectionTestUtils.setField(service, "display", 10);
        ReflectionTestUtils.setField(service, "webTopK", 3);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Mono<List<String>> naverProviderMono(NaverSearchService service, String query) {
        Mono<List<String>> result = (Mono<List<String>>) ReflectionTestUtils.invokeMethod(
                service,
                "callNaverApiMono",
                query,
                NaverSearchService.SearchPolicy.freeMode());
        assertNotNull(result);
        return result;
    }

    private static BraveSearchService braveService(String apiKey, NightmareBreaker breaker) {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                "https://api.search.brave.com/res/v1/web/search",
                apiKey,
                100.0d,
                2000,
                0L,
                200L,
                2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.search.brave.com/res/v1/web/search");
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "nightmareBreaker", breaker);
        service.init();
        return service;
    }

    private static NightmareBreakerProperties breakerProperties() {
        NightmareBreakerProperties properties = new NightmareBreakerProperties();
        properties.setFailureThreshold(10);
        properties.setTimeoutThreshold(10);
        properties.setRateLimitThreshold(10);
        properties.setRejectedThreshold(10);
        properties.setBlankThreshold(10);
        properties.setTripOnSlowCall(false);
        return properties;
    }

    private static final class CountingBreaker extends NightmareBreaker {
        private final AtomicInteger acquireCalls = new AtomicInteger();

        private CountingBreaker() {
            super(breakerProperties());
        }

        @Override
        public NightmareBreaker.CallPermit acquire(String key, String stage) {
            acquireCalls.incrementAndGet();
            return super.acquire(key, stage);
        }
    }
}
