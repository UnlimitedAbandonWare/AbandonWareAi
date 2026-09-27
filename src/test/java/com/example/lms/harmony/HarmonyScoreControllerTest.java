package com.example.lms.harmony;

import com.example.lms.debug.AblationPenaltyBootDumper;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarmonyScoreControllerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void apiControllerExposesScoreAndStreamRoutes() throws Exception {
        RequestMapping root = HarmonyScoreController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/harmony"}, root.value());

        assertArrayEquals(new String[]{"/score"},
                HarmonyScoreController.class.getMethod("getScore").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/stream"},
                HarmonyScoreController.class.getMethod("stream").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/push"},
                HarmonyScoreController.class.getMethod("push").getAnnotation(GetMapping.class).value());
    }

    @Test
    void scoreEndpointReturnsSnapshotBody() {
        RuntimeFixture fixture = runtime(32);
        HarmonyScoreController controller = controller(fixture, new RecordingEmitter(300_000L));

        ResponseEntity<HarmonyScoreSnapshot> response = controller.getScore();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100.0d, response.getBody().goalPoint(), 0.0001d);
    }

    @Test
    void scoreEndpointDoesNotPromoteBootOrRequestBreadcrumbsToRuntimeHarmonyEvidence() {
        TraceStore.clear();
        new AblationPenaltyBootDumper(new MockEnvironment()
                .withProperty("uaw.ablation.penalty.default", "0.20"))
                .onApplicationEvent(null);
        TraceStore.clear();
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.TRUE);

        RuntimeFixture fixture = runtime(32);
        HarmonyScoreController controller = controller(fixture, new RecordingEmitter(300_000L));

        HarmonyScoreSnapshot snapshot = controller.getScore().getBody();

        assertNotNull(snapshot);
        for (String id : java.util.List.of("HB-01", "HB-06", "HB-12")) {
            HarmonyScoreSnapshot.HarmonyBreakEntry entry = snapshot.harmonyBreaks().stream()
                    .filter(candidate -> id.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("BLOCKED_EVIDENCE", entry.status(),
                    "boot baseline and Harmony request breadcrumbs are advisory, not runtime proof for " + id);
        }
    }

    @Test
    void pageControllerRoutesToHarmonyDashboardTemplate() {
        HarmonyDashboardPageController controller = new HarmonyDashboardPageController();

        assertEquals("harmony-dashboard", controller.dashboard());
    }

    @Test
    void completionTimeoutAndErrorCallbacksCloseOneLeaseExactlyOnce() {
        RuntimeFixture fixture = runtime(1);
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        HarmonyScoreController controller = controller(fixture, emitter);

        assertEquals(emitter, controller.stream());
        assertEquals(1, fixture.runtime.activeCount());

        emitter.fireCompletion();
        emitter.fireTimeout();
        emitter.fireError(new IllegalStateException("controlled"));

        assertEquals(0, fixture.runtime.activeCount());
        verify(fixture.futures.get(0), times(1)).cancel(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void sendFailureCompletesEmitterAndClosesLease(String failureMode) {
        RuntimeFixture fixture = runtime(1);
        RecordingEmitter emitter = new RecordingEmitter(300_000L);
        emitter.failureMode.set(failureMode);
        HarmonyScoreController controller = controller(fixture, emitter);

        controller.stream();
        fixture.ticks.get(0).run();

        assertEquals(1, emitter.errorCalls.get());
        assertNotNull(emitter.lastError.get());
        assertEquals(failureMode, TraceStore.get("harmony.score.stream.failureClass"));
        assertEquals(0, fixture.runtime.activeCount());
        verify(fixture.futures.get(0), times(1)).cancel(false);
    }

    @Test
    void runtimeShutdownClosesControllerLease() {
        RuntimeFixture fixture = runtime(1);
        HarmonyScoreController controller = controller(fixture, new RecordingEmitter(300_000L));
        controller.stream();

        fixture.runtime.shutdown();

        assertEquals(0, fixture.runtime.activeCount());
        verify(fixture.futures.get(0), times(1)).cancel(false);
        verify(fixture.scheduler, times(1)).shutdownNow();
    }

    @Test
    void capacityExhaustionReturnsFixed429CompatibleError() {
        RuntimeFixture fixture = runtime(1);
        HarmonySseRuntime.StreamLease occupied = fixture.runtime.open(() -> {
        }).orElseThrow();
        HarmonyScoreController controller = controller(fixture, new RecordingEmitter(300_000L));

        ResponseStatusException rejection = assertThrows(ResponseStatusException.class, controller::stream);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejection.getStatusCode());
        assertEquals("harmony_sse_capacity", rejection.getReason());
        occupied.close();
        assertEquals(0, fixture.runtime.activeCount());
    }

    @Test
    void springComponentScanInjectsTheSingleSharedRuntimeBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.example.lms.harmony");
            context.refresh();

            assertEquals(1, context.getBeansOfType(HarmonySseRuntime.class).size());
            HarmonySseRuntime runtime = context.getBean(HarmonySseRuntime.class);
            HarmonyScoreController controller = context.getBean(HarmonyScoreController.class);
            assertSame(runtime, ReflectionTestUtils.getField(controller, "streamRuntime"));
        }
    }

    private static HarmonyScoreController controller(RuntimeFixture fixture, RecordingEmitter emitter) {
        return new HarmonyScoreController(new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator()), fixture.runtime) {
            @Override
            SseEmitter createEmitter(long timeoutMs) {
                return emitter;
            }
        };
    }

    private static RuntimeFixture runtime(int capacity) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        List<Runnable> ticks = new ArrayList<>();
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L), eq(SECONDS)))
                .thenAnswer(invocation -> {
                    ticks.add(invocation.getArgument(0));
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    futures.add(future);
                    return future;
                });
        return new RuntimeFixture(new HarmonySseRuntime(scheduler, capacity), scheduler, ticks, futures);
    }

    private record RuntimeFixture(
            HarmonySseRuntime runtime,
            ScheduledExecutorService scheduler,
            List<Runnable> ticks,
            List<ScheduledFuture<?>> futures) {
    }

    private static class RecordingEmitter extends SseEmitter {
        private final AtomicReference<Runnable> completion = new AtomicReference<>();
        private final AtomicReference<Runnable> timeout = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        private final AtomicReference<String> failureMode = new AtomicReference<>("");
        private final AtomicInteger errorCalls = new AtomicInteger();
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();

        private RecordingEmitter(long timeoutMs) {
            super(timeoutMs);
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion.set(callback);
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout.set(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error.set(callback);
        }

        @Override
        public void send(SseEventBuilder event) throws IOException {
            if ("io".equals(failureMode.get())) {
                throw new IOException("controlled");
            }
            if ("runtime".equals(failureMode.get())) {
                throw new IllegalStateException("controlled");
            }
        }

        @Override
        public void completeWithError(Throwable failure) {
            errorCalls.incrementAndGet();
            lastError.set(failure);
        }

        private void fireCompletion() {
            completion.get().run();
        }

        private void fireTimeout() {
            timeout.get().run();
        }

        private void fireError(Throwable failure) {
            error.get().accept(failure);
        }
    }
}
