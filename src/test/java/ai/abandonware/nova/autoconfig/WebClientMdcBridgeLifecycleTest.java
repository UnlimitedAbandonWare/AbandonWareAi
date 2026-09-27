package ai.abandonware.nova.autoconfig;

import com.example.lms.api.MessageGatewayProperties;
import com.example.lms.config.WebClientConfig;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebClientMdcBridgeLifecycleTest {
    private static final String RID = "fixture-request";
    private static final String SID = "fixture-session";
    private static final Map<String, String> CALLER = Map.of("fixtureOwner", "caller");

    @BeforeEach
    @AfterEach
    void clearContext() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void synchronousExchangeFailureRestoresCallerContext() {
        MDC.setContextMap(CALLER);
        AtomicBoolean bridgeVisible = new AtomicBoolean();
        WebClient client = client(request -> {
            bridgeVisible.set(bridgeVisible());
            throw new IllegalStateException("fixture-exchange-failure");
        });

        assertThrows(RuntimeException.class, () -> request(client, true).block(Duration.ofSeconds(2)));
        assertAll(
                () -> assertTrue(bridgeVisible.get(), "bridge-present-at-exchange"),
                () -> assertTrue(CALLER.equals(snapshot()), "caller-context-restored-after-synchronous-failure"));
    }

    @ParameterizedTest(name = "terminal={0}")
    @ValueSource(strings = {"success", "error", "cancel"})
    void asynchronousTerminationPreservesBothThreadContexts(String terminal) throws Exception {
        MDC.setContextMap(CALLER);
        AtomicReference<MonoSink<ClientResponse>> pending = new AtomicReference<>();
        AtomicBoolean subscriptionBridge = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        WebClient client = client(request -> Mono.<ClientResponse>create(sink -> {
            subscriptionBridge.set(bridgeVisible());
            pending.set(sink);
        }).doOnCancel(() -> cancelled.set(true)));
        Disposable subscription = request(client, true).subscribe(value -> completions.incrementAndGet(),
                error -> failures.incrementAndGet());
        boolean callerRestoredBeforeTermination = CALLER.equals(snapshot());
        assertNotNull(pending.get(), "recording-transport-subscribed");
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "mdc-lifecycle-fixture"));
        boolean workerRestored;
        long callerThread = Thread.currentThread().getId();
        try {
            workerRestored = worker.submit(() -> {
                Map<String, String> workerContext = Map.of("fixtureOwner", "worker", "trace", "worker-trace");
                MDC.setContextMap(workerContext);
                try {
                    assertTrue(Thread.currentThread().getId() != callerThread, "distinct-terminal-thread");
                    switch (terminal) {
                        case "success" -> pending.get().success(response());
                        case "error" -> pending.get().error(new IllegalStateException("fixture-async-failure"));
                        case "cancel" -> subscription.dispose();
                        default -> throw new AssertionError("unknown-fixture-terminal");
                    }
                    return workerContext.equals(snapshot());
                } finally {
                    MDC.clear();
                    TraceStore.clear();
                }
            }).get(2, TimeUnit.SECONDS);
        } finally {
            subscription.dispose();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS), "fixture-worker-terminated");
        }
        boolean callerRestoredAfterTermination = CALLER.equals(snapshot());
        assertAll(
                () -> assertTrue(subscriptionBridge.get(), "bridge-visible-during-lazy-subscription"),
                () -> assertTrue(callerRestoredBeforeTermination, "caller-restored-while-request-pending"),
                () -> assertTrue(workerRestored, "terminal-thread-keeps-own-context"),
                () -> assertTrue(callerRestoredAfterTermination, "caller-keeps-own-context-after-termination"),
                () -> assertEquals("success".equals(terminal) ? 1 : 0, completions.get()),
                () -> assertEquals("error".equals(terminal) ? 1 : 0, failures.get()),
                () -> assertEquals("cancel".equals(terminal), cancelled.get()));
    }

    @Test
    void lazyExchangeSubscriptionSeesBridgeAndRestoresCaller() {
        MDC.setContextMap(CALLER);
        AtomicBoolean atExchange = new AtomicBoolean();
        AtomicBoolean atSubscription = new AtomicBoolean();
        WebClient client = client(request -> {
            atExchange.set(bridgeVisible());
            return Mono.defer(() -> {
                atSubscription.set(bridgeVisible());
                return Mono.just(response());
            });
        });
        assertEquals(200, request(client, true).block(Duration.ofSeconds(2)));
        assertTrue(atExchange.get(), "bridge-at-exchange-construction");
        assertTrue(atSubscription.get(), "bridge-at-deferred-exchange-subscription");
        assertTrue(CALLER.equals(snapshot()), "caller-restored-after-lazy-exchange");
    }

    @Test
    void reactorSubscriberContextReachesTransport() {
        MDC.setContextMap(CALLER);
        AtomicBoolean contextSeen = new AtomicBoolean();
        WebClient client = client(request -> Mono.deferContextual(context -> {
            contextSeen.set("fixture-value".equals(context.getOrDefault("fixtureContext", "missing")));
            return Mono.just(response());
        }));
        assertEquals(200, request(client, true).contextWrite(context -> context.put("fixtureContext", "fixture-value"))
                .block(Duration.ofSeconds(2)));
        assertTrue(contextSeen.get(), "reactor-context-preserved");
        assertTrue(CALLER.equals(snapshot()), "caller-context-preserved");
    }

    @Test
    void repeatedSubscriptionsUseEachCurrentCallerContext() {
        AtomicReference<String> ownerAtSubscription = new AtomicReference<>();
        WebClient client = client(request -> Mono.defer(() -> {
            ownerAtSubscription.set(MDC.get("fixtureOwner"));
            assertTrue(bridgeVisible(), "bridge-present-for-each-subscription");
            return Mono.just(response());
        }));
        Mono<Integer> exchange = request(client, true);
        for (String owner : new String[]{"first-caller", "second-caller"}) {
            Map<String, String> context = Map.of("fixtureOwner", owner);
            MDC.setContextMap(context);
            assertEquals(200, exchange.block(Duration.ofSeconds(2)));
            assertTrue(owner.equals(ownerAtSubscription.get()), "current-caller-used");
            assertTrue(context.equals(snapshot()), "current-caller-restored");
        }
    }

    @Test
    void absentCorrelationIdsLeaveContextUntouched() {
        MDC.setContextMap(CALLER);
        AtomicBoolean idsAbsent = new AtomicBoolean();
        WebClient client = client(request -> {
            idsAbsent.set(!request.headers().containsKey("X-Request-Id")
                    && !request.headers().containsKey("X-Session-Id"));
            return Mono.just(response());
        });
        assertEquals(200, request(client, false).block(Duration.ofSeconds(2)));
        assertTrue(idsAbsent.get(), "no-unrequested-ids");
        assertTrue(CALLER.equals(snapshot()), "no-id-context-unchanged");
    }

    @Test
    void existingCorrelationContextIsPreserved() {
        Map<String, String> context = Map.of("x-request-id", "existing-request", "trace", "existing-trace",
                "traceId", "existing-trace-id", "sid", "existing-session", "sessionId", "existing-session-id");
        MDC.setContextMap(context);
        AtomicBoolean originalVisible = new AtomicBoolean();
        WebClient client = client(request -> {
            originalVisible.set(context.equals(snapshot()));
            return Mono.just(response());
        });
        assertEquals(200, request(client, true).block(Duration.ofSeconds(2)));
        assertTrue(originalVisible.get(), "existing-values-not-overwritten");
        assertTrue(context.equals(snapshot()), "existing-context-preserved");
    }

    private static WebClient client(ExchangeFunction exchange) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        new NovaDebugPortAutoConfiguration().novaWebClientCorrelationCustomizer(
                new DefaultListableBeanFactory().getBeanProvider(DebugEventStore.class),
                new MockEnvironment().withProperty("nova.orch.debug.webclient-correlation.generate-missing-ids", "false"))
                .customize(builder);
        WebClientConfig config = new WebClientConfig(mock(MessageGatewayProperties.class));
        ReflectionTestUtils.setField(config, "naverSearchApiBaseUrl", "https://example.test");
        return config.naverWebClient(builder);
    }

    private static Mono<Integer> request(WebClient client, boolean withIds) {
        WebClient.RequestHeadersSpec<?> request = client.get().uri("/fixture");
        if (withIds) {
            request = request.header("X-Request-Id", RID).header("X-Session-Id", SID);
        }
        return request.exchangeToMono(response -> Mono.just(response.statusCode().value()));
    }

    private static boolean bridgeVisible() {
        return RID.equals(MDC.get("x-request-id")) && SID.equals(MDC.get("sid"));
    }

    private static Map<String, String> snapshot() {
        Map<String, String> map = MDC.getCopyOfContextMap();
        return map == null ? Map.of() : Map.copyOf(map);
    }

    private static ClientResponse response() {
        return ClientResponse.create(HttpStatus.OK).build();
    }
}
