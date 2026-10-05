package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DebugEventStoreNdjsonConcurrencyTest {

    private DebugEventStore store;

    @AfterEach
    void cleanup() throws Exception {
        if (store != null) {
            store.shutdownNdjsonWriter();
            assertTrue(store.awaitNdjsonWriterTermination(2, TimeUnit.SECONDS));
        }
        TraceStore.clear();
    }

    @Test
    void slowWriterDoesNotBlockEmitAndBoundedFifoDropsNewest() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch twoWritesCompleted = new CountDownLatch(2);
        CopyOnWriteArrayList<String> writtenLines = new CopyOnWriteArrayList<>();
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicReference<Thread> workerThread = new AtomicReference<>();

        DebugEventStore.NdjsonLineWriter writer = (directory, fileName, jsonLine) -> {
            if (writeCalls.incrementAndGet() == 1) {
                firstWriteStarted.countDown();
                assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS));
            }
            writtenLines.add(jsonLine);
            twoWritesCompleted.countDown();
        };

        store = new DebugEventStore(1, writer, task -> {
            Thread thread = new Thread(task, "debug-ndjson-test-worker");
            thread.setDaemon(true);
            workerThread.set(thread);
            return thread;
        });
        configure(store);

        String rawPrivateValue = "ownerToken=private-debug-value";
        emit(store, "fp-first", rawPrivateValue);
        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofMillis(750), () -> {
            emit(store, "fp-second", rawPrivateValue);
            emit(store, "fp-third", rawPrivateValue);
        });

        assertEquals(1, store.ndjsonQueueSize());
        assertEquals(1L, store.ndjsonDroppedCount());
        Map<String, Object> saturated = mirrorStats(store);
        assertEquals(2L, saturated.get("acceptedCount"));
        assertEquals(0L, saturated.get("writeSuccessCount"));
        assertEquals(0L, saturated.get("writeFailureCount"));
        assertEquals(0L, saturated.get("abandonedCount"));
        assertEquals(1L, saturated.get("droppedCount"));
        assertEquals(1, saturated.get("queueDepth"));
        assertFalse(saturated.toString().contains(rawPrivateValue));
        assertTrue(workerThread.get().isDaemon());

        releaseFirstWrite.countDown();
        assertTrue(twoWritesCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(
                SafeRedactor.hashValue("fp-first"),
                SafeRedactor.hashValue("fp-second")), fingerprints(writtenLines));
        assertFalse(String.join("\n", writtenLines).contains(rawPrivateValue));
        assertEquals(0, store.ndjsonQueueSize());
        store.shutdownNdjsonWriter();
        assertTrue(store.awaitNdjsonWriterTermination(2, TimeUnit.SECONDS));
        assertEquals(2L, mirrorStats(store).get("writeSuccessCount"));
        assertEquals(0L, mirrorStats(store).get("writeFailureCount"));
        assertEquals(0L, mirrorStats(store).get("abandonedCount"));
    }

    @Test
    void diskFullFailureIsCountedAndNextWriteRecoversWithoutBlockingEmit() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        CopyOnWriteArrayList<String> writtenLines = new CopyOnWriteArrayList<>();
        String rawPrivateValue = "ownerToken=private-disk-full-value";
        store = new DebugEventStore(1, (directory, fileName, jsonLine) -> {
            if (writeCalls.incrementAndGet() == 1) {
                firstWriteStarted.countDown();
                assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS));
                throw new IOException("disk_full " + rawPrivateValue);
            }
            writtenLines.add(jsonLine);
        }, task -> {
            Thread thread = new Thread(task, "debug-ndjson-failure-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        configure(store);

        emit(store, "fp-disk-full", rawPrivateValue);
        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofMillis(750),
                () -> emit(store, "fp-recovered", rawPrivateValue));
        releaseFirstWrite.countDown();
        store.shutdownNdjsonWriter();
        assertTrue(store.awaitNdjsonWriterTermination(2, TimeUnit.SECONDS));

        Map<String, Object> stats = mirrorStats(store);
        assertEquals(Map.of("counterScope", "process_lifetime", "enabled", true,
                "acceptedCount", 2L, "writeSuccessCount", 1L, "writeFailureCount", 1L,
                "abandonedCount", 0L, "droppedCount", 0L, "queueDepth", 0), stats);
        assertEquals(List.of(SafeRedactor.hashValue("fp-recovered")), fingerprints(writtenLines));
        assertFalse(String.join("\n", writtenLines).contains(rawPrivateValue));
        assertFalse(stats.toString().contains(rawPrivateValue));
    }

    @Test
    void interruptedShutdownCountsQueuedAbandonmentAndPreservesInterruptFlags() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicBoolean workerInterruptPreserved = new AtomicBoolean();
        CountDownLatch workerFinished = new CountDownLatch(1);
        String rawPrivateValue = "ownerToken=private-shutdown-value";
        store = new DebugEventStore(1, (directory, fileName, jsonLine) -> {
            writeCalls.incrementAndGet();
            firstWriteStarted.countDown();
            releaseFirstWrite.await();
        }, task -> {
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    workerInterruptPreserved.set(Thread.currentThread().isInterrupted());
                    workerFinished.countDown();
                }
            }, "debug-ndjson-shutdown-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        configure(store);

        emit(store, "fp-shutdown-active", rawPrivateValue);
        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));
        emit(store, "fp-shutdown-queued", rawPrivateValue);
        assertEquals(1, store.ndjsonQueueSize());
        try {
            Thread.currentThread().interrupt();
            store.shutdownNdjsonWriter();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            releaseFirstWrite.countDown();
        }
        assertTrue(store.awaitNdjsonWriterTermination(2, TimeUnit.SECONDS));
        assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
        assertTrue(workerInterruptPreserved.get());
        assertEquals(1, writeCalls.get());
        Map<String, Object> stats = mirrorStats(store);
        assertEquals(Map.of("counterScope", "process_lifetime", "enabled", true,
                "acceptedCount", 2L, "writeSuccessCount", 0L, "writeFailureCount", 1L,
                "abandonedCount", 1L, "droppedCount", 0L, "queueDepth", 0), stats);
        assertFalse(stats.toString().contains(rawPrivateValue));
    }

    private static Map<String, Object> mirrorStats(DebugEventStore target) {
        return ReflectionTestUtils.invokeMethod(target, "ndjsonMirrorStats");
    }

    private static void configure(DebugEventStore target) {
        ReflectionTestUtils.setField(target, "enabled", true);
        ReflectionTestUtils.setField(target, "maxSize", 20);
        ReflectionTestUtils.setField(target, "windowMs", 60_000L);
        ReflectionTestUtils.setField(target, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(target, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(target, "ndjsonEnabled", true);
        ReflectionTestUtils.setField(target, "ndjsonDir", "unused-by-test-writer");
    }

    private static void emit(DebugEventStore target, String fingerprint, String rawPrivateValue) {
        target.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                fingerprint,
                "fixed diagnostic message",
                "DebugEventStoreNdjsonConcurrencyTest",
                Map.of("query", rawPrivateValue, "provider", "test"),
                null);
    }

    private static List<String> fingerprints(List<String> jsonLines) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return jsonLines.stream()
                .map(line -> {
                    try {
                        return mapper.readTree(line).path("fingerprint").asText();
                    } catch (Exception e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .toList();
    }
}
