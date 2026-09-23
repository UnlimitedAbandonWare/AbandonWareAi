package com.example.lms.service;

import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaverSearchServiceInterruptContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void interruptHelperRestoresInterruptedFlag() {
        Thread.interrupted();
        try {
            NaverSearchService.restoreInterruptFlag();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void naverInterruptedExceptionPathsDoNotClearInterruptFlag() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Thread.interrupted();"));
        assertTrue(source.contains("Thread.currentThread().interrupt();"));
    }

    @Test
    void cancellationExceptionEmitsCancelledTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String rawQuery = "private naver cancelled query";
        String rawSecret = "secret-ownerToken-value";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new IllegalStateException("wrapped provider cancellation",
                                new CancellationException("cancelled ownerToken=" + rawSecret + " query=" + rawQuery))))
                .build();
        NaverSearchService service = naverService(webClient, "id:" + rawSecret);

        var out = service.searchSnippetsMono(rawQuery, 3).block(Duration.ofSeconds(3));

        assertTrue(out == null || out.isEmpty());
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.timeout"));
        assertEquals("cancelled", TraceStore.get("web.naver.failureReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.naver.exceptionType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertFalse(trace.contains("CancellationException"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void timingOutOneWaiterDoesNotCancelSharedCacheComputation() throws Exception {
        CountDownLatch providerSubscribed = new CountDownLatch(1);
        AtomicBoolean providerCancelled = new AtomicBoolean();
        Sinks.One<ClientResponse> response = Sinks.one();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    providerSubscribed.countDown();
                    return response.asMono().doOnCancel(() -> providerCancelled.set(true));
                })
                .build();
        NaverSearchService service = naverService(webClient, "test-client:test-secret");
        ReflectionTestUtils.setField(service, "display", 20);
        ReflectionTestUtils.setField(service, "webTopK", 8);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 5_000L);

        Mono<?> first = ReflectionTestUtils.invokeMethod(
                service,
                "loadNaverAttempt",
                "shared cache waiter query",
                NaverSearchService.SearchPolicy.freeMode(),
                5_000L,
                Map.of());
        CompletableFuture<?> firstWaiter = first.toFuture();
        assertTrue(providerSubscribed.await(1, TimeUnit.SECONDS));

        Mono<?> second = ReflectionTestUtils.invokeMethod(
                service,
                "loadNaverAttempt",
                "shared cache waiter query",
                NaverSearchService.SearchPolicy.freeMode(),
                150L,
                Map.of());
        CompletableFuture<?> secondWaiter = second.toFuture();

        // The cache load has its own live owner; only the dependent waiter expires.
        Object secondOutcome = secondWaiter.get(2, TimeUnit.SECONDS);
        assertTrue(((List<?>) ReflectionTestUtils.getField(secondOutcome, "snippets")).isEmpty());
        assertFalse(firstWaiter.isDone(), "shared owner must still be awaiting the held response");
        assertFalse(providerCancelled.get(), "one waiter timeout must not cancel the cache owner");

        assertEquals(Sinks.EmitResult.OK, response.tryEmitValue(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"items\":[{\"title\":\"result\",\"link\":\"https://example.com/a\",\"description\":\"usable\"}]}")
                .build()));
        Object firstOutcome = firstWaiter.get(2, TimeUnit.SECONDS);
        List<?> snippets = (List<?>) ReflectionTestUtils.getField(firstOutcome, "snippets");
        assertEquals(1, snippets.size());
        assertFalse(providerCancelled.get());
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naverService(WebClient webClient, String naverKeys) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        when(ratePolicy.allowedExpansions()).thenReturn(1);
        when(ratePolicy.currentDelayMs()).thenReturn(0L);

        GuardProfileProps guardProfileProps = mock(GuardProfileProps.class);
        when(guardProfileProps.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);

        NaverSearchService service = new NaverSearchService(
                mock(QueryTransformer.class),
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
        ReflectionTestUtils.setField(service, "similarThreshold", 0.86d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        return service;
    }
}
