package com.example.lms.debug;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.api.DebugEventsDiagnosticsController;
import com.example.lms.api.DebugEventsSseRuntime;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DebugEventStoreFingerprintRetentionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void fingerprintAggregationEvictsOldestStateWithoutEvictingRingEvents() {
        DebugEventStore store = configuredStore(10, 20L);
        configureFingerprintCapIfPresent(store, 2);

        emit(store, "fp-a");
        emit(store, "fp-b");
        emit(store, "fp-c");

        assertEquals(3, store.list(10).size());
        assertEquals(hashedFingerprints("fp-b", "fp-c"), fingerprints(store));
    }

    @Test
    void fingerprintAggregationKeepsHotStateAndItsCounters() {
        DebugEventStore store = configuredStore(10, 1L);
        configureFingerprintCapIfPresent(store, 2);

        emit(store, "fp-hot");
        emit(store, "fp-cold");
        emit(store, "fp-hot");
        emit(store, "fp-new");

        Map<String, Object> hot = aggregate(store, "fp-hot");
        assertNotNull(hot);
        assertEquals(2L, longValue(hot, "windowCount"));
        assertEquals(1L, longValue(hot, "suppressedInWindow"));
        assertEquals(2L, longValue(hot, "total"));
        assertEquals(1L, longValue(hot, "totalSuppressed"));
        assertEquals(hashedFingerprints("fp-hot", "fp-new"), fingerprints(store));
    }

    @Test
    void evictedFingerprintReentryStartsNewAggregationLifetime() {
        DebugEventStore store = configuredStore(10, 20L);
        configureFingerprintCapIfPresent(store, 2);

        emit(store, "fp-a");
        emit(store, "fp-b");
        emit(store, "fp-c");
        emit(store, "fp-a");

        Map<String, Object> reentered = aggregate(store, "fp-a");
        assertNotNull(reentered);
        assertEquals(1L, longValue(reentered, "windowCount"));
        assertEquals(0L, longValue(reentered, "suppressedInWindow"));
        assertEquals(1L, longValue(reentered, "total"));
        assertEquals(0L, longValue(reentered, "totalSuppressed"));
        assertEquals(hashedFingerprints("fp-c", "fp-a"), fingerprints(store));
    }

    @Test
    void concurrentUniqueFingerprintAdmissionNeverExceedsConfiguredCap() throws Exception {
        int taskCount = 64;
        int aggregateCap = 8;
        DebugEventStore store = configuredStore(128, 100L);
        configureFingerprintCapIfPresent(store, aggregateCap);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                String fingerprint = "fp-concurrent-" + i;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    emit(store, fingerprint);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            List<Map<String, Object>> aggregates = store.listFingerprints(500);
            assertTrue(aggregates.size() <= aggregateCap);
            assertEquals(aggregateCap, aggregates.size());
            assertEquals(taskCount, store.list(taskCount).size());
            assertTrue(aggregates.stream().allMatch(row -> row != null && row.get("fingerprint") != null));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonpositiveFingerprintCapFallsBackToPositiveRingCap() {
        DebugEventStore store = configuredStore(2, 20L);
        configureFingerprintCapIfPresent(store, 0);

        emit(store, "fp-a");
        emit(store, "fp-b");
        emit(store, "fp-c");

        assertEquals(2, store.list(10).size());
        assertEquals(2, store.listFingerprints(10).size());
    }

    @Test
    void loweringConfiguredCapTrimsExistingStatesAndPreservesCurrentCounters() {
        DebugEventStore store = configuredStore(10, 20L);
        configureFingerprintCapIfPresent(store, 3);
        emit(store, "fp-a");
        emit(store, "fp-b");
        emit(store, "fp-c");

        ReflectionTestUtils.setField(store, "maxFingerprints", 1);
        emit(store, "fp-c");

        assertEquals(hashedFingerprints("fp-c"), fingerprints(store));
        Map<String, Object> retained = aggregate(store, "fp-c");
        assertNotNull(retained);
        assertEquals(2L, longValue(retained, "windowCount"));
        assertEquals(2L, longValue(retained, "total"));
    }

    @Test
    void loweringFallbackRingCapTrimsExistingAggregateStates() {
        DebugEventStore store = configuredStore(3, 20L);
        configureFingerprintCapIfPresent(store, 0);
        emit(store, "fp-a");
        emit(store, "fp-b");
        emit(store, "fp-c");

        ReflectionTestUtils.setField(store, "maxSize", 1);
        emit(store, "fp-b");

        assertEquals(hashedFingerprints("fp-b"), fingerprints(store));
        Map<String, Object> retained = aggregate(store, "fp-b");
        assertNotNull(retained);
        assertEquals(2L, longValue(retained, "windowCount"));
        assertEquals(2L, longValue(retained, "total"));
    }

    @Test
    void evictionDoesNotRetainSecretLikeFingerprintInAggregateOrTrace() {
        DebugEventStore store = configuredStore(3, 20L);
        configureFingerprintCapIfPresent(store, 1);
        String rawFingerprint = "ownerToken=private-debug-fingerprint";

        emit(store, rawFingerprint);
        emit(store, "fp-safe");

        String publicAggregate = String.valueOf(store.listFingerprints(10));
        assertFalse(publicAggregate.contains(rawFingerprint));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawFingerprint));
        assertEquals(hashedFingerprints("fp-safe"), fingerprints(store));
    }

    @Test
    void rawFingerprintIsHashedOnceBeforeEveryOutputSurface() throws Exception {
        String rawFingerprint = "RAW_PRIVATE_FP_55";
        String expectedFingerprint = SafeRedactor.hashValue(rawFingerprint);
        assertNotNull(expectedFingerprint);
        assertTrue(expectedFingerprint.matches("hash:[0-9a-f]{12}"));
        assertEquals(17, expectedFingerprint.length());

        CountDownLatch ndjsonWrites = new CountDownLatch(2);
        CopyOnWriteArrayList<String> ndjsonLines = new CopyOnWriteArrayList<>();
        DebugEventStore store = new DebugEventStore(
                8,
                (directory, fileName, jsonLine) -> {
                    ndjsonLines.add(jsonLine);
                    ndjsonWrites.countDown();
                },
                task -> {
                    Thread thread = new Thread(task, "debug-fingerprint-test-writer");
                    thread.setDaemon(true);
                    return thread;
                });
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 10);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 1L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 1L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", true);
        ReflectionTestUtils.setField(store, "ndjsonDir", "unused-by-test-writer");

        Logger jsonLogger = (Logger) LoggerFactory.getLogger("DEBUG_EVENT_JSON");
        Level previousLevel = jsonLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        jsonLogger.addAppender(appender);
        jsonLogger.setLevel(Level.INFO);

        try {
            emit(store, rawFingerprint);
            long firstTimestamp = store.list(1).get(0).tsMs();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (System.currentTimeMillis() <= firstTimestamp) {
                    Thread.onSpinWait();
                }
            });
            emit(store, rawFingerprint);

            assertTrue(ndjsonWrites.await(2, TimeUnit.SECONDS));
            List<DebugEvent> events = store.list(10);
            assertEquals(2, events.size());
            assertEquals(Set.of(expectedFingerprint),
                    events.stream().map(DebugEvent::fingerprint).collect(java.util.stream.Collectors.toSet()));

            DebugEvent summary = events.stream()
                    .filter(event -> event.message().startsWith("[rate-limit]"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(summary.message().contains(expectedFingerprint));
            assertEquals(expectedFingerprint, summary.data().get("fingerprint"));

            List<Map<String, Object>> hotspots = store.listFingerprints(10);
            assertEquals(1, hotspots.size());
            assertEquals(expectedFingerprint, hotspots.get(0).get("fingerprint"));

            String ndjson = String.join("\n", ndjsonLines);
            String loggedJson = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);

            MockMvc mvc = MockMvcBuilders.standaloneSetup(new DebugEventsDiagnosticsController(
                    store,
                    mock(DebugEventsSseRuntime.class),
                    1_000L)).build();
            String eventsJson = mvc.perform(get("/api/diagnostics/debug/events").param("limit", "10"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String hotspotsJson = mvc.perform(get("/api/diagnostics/debug/fingerprints").param("limit", "10"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            Method sendEvent = DebugEventsDiagnosticsController.class
                    .getDeclaredMethod("sendEvent", SseEmitter.class, DebugEvent.class);
            sendEvent.setAccessible(true);
            sendEvent.invoke(null, emitter, summary);
            DebugEvent sseEvent = emitter.data.stream()
                    .filter(DebugEvent.class::isInstance)
                    .map(DebugEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertSame(summary, sseEvent);

            List<String> surfaces = List.of(
                    String.valueOf(events),
                    summary.message(),
                    String.valueOf(summary.data()),
                    String.valueOf(hotspots),
                    ndjson,
                    loggedJson,
                    eventsJson,
                    hotspotsJson,
                    String.valueOf(sseEvent));
            for (String surface : surfaces) {
                assertFalse(surface.contains(rawFingerprint), surface);
                assertTrue(surface.contains(expectedFingerprint), surface);
            }
        } finally {
            jsonLogger.detachAppender(appender);
            jsonLogger.setLevel(previousLevel);
            store.shutdownNdjsonWriter();
            assertTrue(store.awaitNdjsonWriterTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullAndBlankFingerprintsShareOneHashedDefaultLabel() {
        DebugEventStore store = configuredStore(10, 20L);
        String message = "fixed default fingerprint diagnostic";
        String rawDefaultFingerprint = Integer.toHexString(Objects.hash(DebugProbeType.GENERIC + "|" + message));
        String expectedFingerprint = SafeRedactor.hashValue(rawDefaultFingerprint);

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                null,
                message,
                Map.of("provider", "test"),
                null);
        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                " \t ",
                message,
                Map.of("provider", "test"),
                null);

        List<DebugEvent> events = store.list(10);
        List<Map<String, Object>> hotspots = store.listFingerprints(10);
        assertEquals(2, events.size());
        assertEquals(Set.of(expectedFingerprint),
                events.stream().map(DebugEvent::fingerprint).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, hotspots.size());
        assertEquals(expectedFingerprint, hotspots.get(0).get("fingerprint"));
        assertTrue(events.stream().noneMatch(event -> rawDefaultFingerprint.equals(event.fingerprint())));
        assertTrue(hotspots.stream().noneMatch(row -> rawDefaultFingerprint.equals(row.get("fingerprint"))));
    }

    @Test
    void probeScopeSequentialTerminalCallsEmitOnlyTheFirstOutcome() {
        DebugEventStore successFirst = configuredStore(4, 10L);
        DebugEventStore.ProbeScope successful = successFirst.probe(DebugProbeType.GENERIC, "scope-success", "synthetic", Map.of());
        successful.success(Map.of("status", "completed"));
        successful.failure(new IllegalStateException("synthetic"), Map.of());
        successful.success(Map.of());
        assertEquals(1, successFirst.list(4).size());
        assertEquals(DebugEventLevel.INFO, successFirst.list(4).get(0).level());

        DebugEventStore failureFirst = configuredStore(4, 10L);
        DebugEventStore.ProbeScope failed = failureFirst.probe(DebugProbeType.GENERIC, "scope-failure", "synthetic", Map.of());
        failed.failure(new IllegalStateException("synthetic"), Map.of());
        failed.success(Map.of());
        failed.failure(new IllegalStateException("synthetic"), Map.of());
        assertEquals(1, failureFirst.list(4).size());
        assertEquals(DebugEventLevel.WARN, failureFirst.list(4).get(0).level());

        DebugEventStore disabled = configuredStore(4, 10L);
        ReflectionTestUtils.setField(disabled, "enabled", false);
        DebugEventStore.ProbeScope noop = disabled.probe(DebugProbeType.GENERIC, "scope-disabled", "synthetic", Map.of());
        noop.success(Map.of());
        noop.failure(new IllegalStateException("synthetic"), Map.of());
        assertTrue(disabled.list(4).isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void concurrentProbeSuccessAndFailureEmitOneTerminalEvent() throws Exception {
        java.util.concurrent.CyclicBarrier begin = new java.util.concurrent.CyclicBarrier(3);
        java.util.concurrent.CyclicBarrier end = new java.util.concurrent.CyclicBarrier(3);
        java.util.concurrent.atomic.AtomicReference<DebugEventStore.ProbeScope> scope = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "r09-owned-terminal-racer");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> workers = new ArrayList<>();
        Logger jsonLogger = (Logger) LoggerFactory.getLogger("DEBUG_EVENT_JSON");
        Level previousLevel = jsonLogger.getLevel();
        jsonLogger.setLevel(Level.OFF);
        try {
            for (int side = 0; side < 2; side++) {
                final boolean succeeds = side == 0;
                workers.add(pool.submit(() -> {
                    while (true) {
                        begin.await(2, TimeUnit.SECONDS);
                        if (stopping.get()) return null;
                        if (succeeds) scope.get().success(Map.of());
                        else scope.get().failure(new IllegalStateException("synthetic"), Map.of());
                        end.await(2, TimeUnit.SECONDS);
                    }
                }));
            }
            for (int round = 0; round < 10_000; round++) {
                DebugEventStore store = configuredStore(4, 10L);
                scope.set(store.probe(DebugProbeType.GENERIC, "r09-synthetic", "bounded terminal race", Map.of()));
                begin.await(2, TimeUnit.SECONDS);
                end.await(2, TimeUnit.SECONDS);
                assertEquals(1, store.list(4).size(), "one scope must emit exactly one terminal event, round=" + round);
            }
            stopping.set(true);
            begin.await(2, TimeUnit.SECONDS);
            for (Future<?> worker : workers) worker.get(2, TimeUnit.SECONDS);
        } finally {
            stopping.set(true);
            pool.shutdownNow();
            jsonLogger.setLevel(previousLevel);
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS), "task-owned workers must terminate");
        }
    }

    private static DebugEventStore configuredStore(int ringCapacity, long maxPerWindow) {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", ringCapacity);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", maxPerWindow);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static void configureFingerprintCapIfPresent(DebugEventStore store, int capacity) {
        Field field = ReflectionUtils.findField(DebugEventStore.class, "maxFingerprints");
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
            ReflectionUtils.setField(field, store, capacity);
        }
    }

    private static void emit(DebugEventStore store, String fingerprint) {
        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                fingerprint,
                "fixed diagnostic message",
                Map.of("provider", "test"),
                null);
    }

    private static Set<String> fingerprints(DebugEventStore store) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> row : store.listFingerprints(500)) {
            out.add(String.valueOf(row.get("fingerprint")));
        }
        return out;
    }

    private static Map<String, Object> aggregate(DebugEventStore store, String fingerprint) {
        String expected = SafeRedactor.hashValue(fingerprint);
        return store.listFingerprints(500).stream()
                .filter(row -> expected.equals(row.get("fingerprint")))
                .findFirst()
                .orElse(null);
    }

    private static Set<String> hashedFingerprints(String... fingerprints) {
        return Set.copyOf(Arrays.stream(fingerprints)
                .map(SafeRedactor::hashValue)
                .toList());
    }

    private static long longValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<Object> data = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            event.build().forEach(item -> data.add(item.getData()));
        }
    }
}
