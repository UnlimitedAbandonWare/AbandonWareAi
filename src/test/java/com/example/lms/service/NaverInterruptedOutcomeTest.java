package com.example.lms.service;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NaverInterruptedOutcomeTest {
    private static final String QUERY = "synthetic interruption probe";

    @AfterEach
    void clearContext() {
        Thread.interrupted();
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wireInterruptionMatchesNeutralBreakerCompletion(boolean wrapped) {
        InterruptedException interruption = new InterruptedException("synthetic wire interruption");
        Throwable error = wrapped ? new IllegalStateException("synthetic wrapper", interruption) : interruption;
        NightmareBreaker breaker = new NightmareBreaker(new NightmareBreakerProperties());
        breaker.signalFailure(NightmareKeys.WEBSEARCH_NAVER, NightmareBreaker.FailureKind.UNKNOWN, null, "seed");
        AtomicInteger wireCalls = new AtomicInteger();

        observe(error, breaker, wireCalls);

        assertEquals(1, wireCalls.get());
        assertEquals(1, breaker.inspect(NightmareKeys.WEBSEARCH_NAVER).consecutiveFailures,
                "interruption must neither count as provider failure nor erase prior adverse state");
        assertEquals(0, breaker.inspect(NightmareKeys.WEBSEARCH_NAVER).consecutiveSuccesses);
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.naver.failureReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(QUERY));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic wire interruption"));
    }

    @Test
    void ordinaryIoFailureDoesNotBecomeCancellation() {
        observe(new IOException("synthetic io failure"), null, new AtomicInteger());
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.cancelled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.timeout"));
        assertEquals("exception", TraceStore.get("web.naver.failureReason"));
    }

    @Test
    void socketTimeoutRemainsTimeout() {
        observe(new SocketTimeoutException("synthetic socket timeout"), null, new AtomicInteger());
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.cancelled"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.timeout"));
        assertEquals("timeout", TraceStore.get("web.naver.failureReason"));
    }

    @SuppressWarnings("unchecked")
    private static void observe(Throwable error, NightmareBreaker breaker, AtomicInteger wireCalls) {
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            wireCalls.incrementAndGet();
            return Mono.error(error);
        }).build();
        NaverSearchService service = new NaverSearchService(null,
                mock(MemoryReinforcementService.class), mock(ObjectProvider.class),
                mock(EmbeddingStore.class), mock(EmbeddingModel.class), (Supplier<Long>) () -> null, null,
                "naver-unit-id:naver-unit-value-123456", "", "", 16L, 30L,
                mock(PlatformTransactionManager.class), mock(RateLimitPolicy.class), client,
                mock(ObjectProvider.class), null);
        ReflectionTestUtils.setField(service, "nightmareBreaker", breaker);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1_000L);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 0);
        ReflectionTestUtils.setField(service, "display", 10);
        ReflectionTestUtils.setField(service, "webTopK", 3);
        Mono<List<String>> wire = ReflectionTestUtils.invokeMethod(service, "callNaverApiMono",
                QUERY, NaverSearchService.SearchPolicy.freeMode());
        assertNotNull(wire);
        // Inspect the real subscribed wire boundary; recover only in this test to read its outcome.
        wire.onErrorResume(failure -> Mono.just(List.of())).block(Duration.ofSeconds(2));
    }
}
