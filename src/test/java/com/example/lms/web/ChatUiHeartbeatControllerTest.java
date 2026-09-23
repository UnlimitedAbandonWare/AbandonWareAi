package com.example.lms.web;

import com.example.lms.agent.context.AgentPipelineHealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatUiHeartbeatControllerTest {

    @Test
    void recoveryStateRemainsFreshWhileGeneralHeartbeatIsCached() {
        AgentPipelineHealthController pipeline = mock(AgentPipelineHealthController.class);
        when(pipeline.pipelineHealth()).thenReturn(Map.of("status", "OK"));
        ChatUiHeartbeatController controller = controller(pipeline, new AtomicLong(1L));
        var phase = new java.util.concurrent.atomic.AtomicReference<>("MODEL_WARMING");
        var manager = new com.example.lms.config.LocalLlmProcessManager() {
            @Override public Map<String, Object> diagnostics() {
                return Map.of("state", phase.get(), "modelReady", "READY".equals(phase.get()));
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "localLlmProcessManager", manager);
        assertEquals("MODEL_WARMING", ((Map<?, ?>) controller.uiHeartbeat().get("localLlmRecovery")).get("state"));
        phase.set("COOLDOWN");
        assertEquals("COOLDOWN", ((Map<?, ?>) controller.uiHeartbeat().get("localLlmRecovery")).get("state"));
        verify(pipeline, times(1)).pipelineHealth();
    }

    @Test
    void repeatedAndConcurrentPollsShareOneThirtySecondSnapshot() throws Exception {
        AtomicInteger probeInvocations = new AtomicInteger();
        AgentPipelineHealthController pipeline = mock(AgentPipelineHealthController.class);
        when(pipeline.pipelineHealth()).thenAnswer(ignored -> {
            probeInvocations.incrementAndGet();
            return Map.of("status", "OK", "reason", "ready", "nextAction", "none");
        });
        AtomicLong now = new AtomicLong(1_000_000L);
        ChatUiHeartbeatController controller = controller(pipeline, now);
        int callers = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return controller.uiHeartbeat();
                }));
            }
            start.countDown();
            for (Future<Map<String, Object>> future : futures) {
                assertEquals("OK", future.get(5, TimeUnit.SECONDS).get("statusBand"));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(1, probeInvocations.get());

        now.addAndGet(Duration.ofSeconds(29).toNanos());
        Map<String, Object> cached = controller.uiHeartbeat();
        assertEquals(1, probeInvocations.get());
        assertEquals(29_000L, cached.get("ageMs"));

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        Map<String, Object> refreshed = controller.uiHeartbeat();
        assertEquals(2, probeInvocations.get());
        assertEquals(0L, refreshed.get("ageMs"));
    }

    @Test
    void failedRefreshReplacesExpiredHealthyEntryWithFixedImmutableUnavailableProjection() {
        AgentPipelineHealthController pipeline = mock(AgentPipelineHealthController.class);
        when(pipeline.pipelineHealth())
                .thenReturn(Map.of("status", "OK"))
                .thenThrow(new IllegalStateException("PRIVATE_SENTINEL_68"));
        AtomicLong now = new AtomicLong(5_000_000L);
        AtomicInteger fallbackChecks = new AtomicInteger();
        ChatUiHeartbeatController controller = controller(
                provider(pipeline),
                countingProvider(null, fallbackChecks),
                now);

        Map<String, Object> healthy = controller.uiHeartbeat();
        assertEquals("OK", healthy.get("statusBand"));

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        Map<String, Object> unavailable = controller.uiHeartbeat();
        assertEquals(Map.of(
                "statusBand", "WARN",
                "reasonCode", "heartbeat_unavailable",
                "nextAction", "retry_heartbeat",
                "ageMs", 0L), unavailable);
        assertFalse(unavailable.toString().contains("PRIVATE_SENTINEL_68"));
        assertEquals(0, fallbackChecks.get(), "pipeline failure must not start the core fallback");
        assertThrows(UnsupportedOperationException.class, () -> unavailable.put("statusBand", "OK"));

        assertEquals(unavailable, controller.uiHeartbeat());
        verify(pipeline, times(2)).pipelineHealth();
    }

    @Test
    void absentPipelineBeanUsesCoreFallbackOnlyOncePerCacheWindow() {
        AtomicInteger availabilityChecks = new AtomicInteger();
        AtomicLong now = new AtomicLong(9_000_000L);
        ChatUiHeartbeatController controller = controller(
                countingProvider(null, availabilityChecks),
                provider(null),
                now);

        Map<String, Object> first = controller.uiHeartbeat();
        Map<String, Object> second = controller.uiHeartbeat();
        assertEquals(first.keySet(), second.keySet());
        assertEquals(1, availabilityChecks.get());

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        controller.uiHeartbeat();
        assertEquals(2, availabilityChecks.get());
    }

    private static ChatUiHeartbeatController controller(
            AgentPipelineHealthController pipeline,
            AtomicLong now
    ) {
        return controller(provider(pipeline), provider(null), now);
    }

    private static ChatUiHeartbeatController controller(
            ObjectProvider<AgentPipelineHealthController> pipelineProvider,
            ObjectProvider<ApplicationContext> applicationContextProvider,
            AtomicLong now
    ) {
        return new ChatUiHeartbeatController(
                pipelineProvider,
                applicationContextProvider,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                now::get,
                Duration.ofSeconds(30));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return countingProvider(value, null);
    }

    private static <T> ObjectProvider<T> countingProvider(T value, AtomicInteger availabilityChecks) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                if (availabilityChecks != null) {
                    availabilityChecks.incrementAndGet();
                }
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
