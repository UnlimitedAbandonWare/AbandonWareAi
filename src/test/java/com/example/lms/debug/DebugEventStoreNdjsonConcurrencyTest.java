package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        assertTrue(workerThread.get().isDaemon());

        releaseFirstWrite.countDown();
        assertTrue(twoWritesCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(
                SafeRedactor.hashValue("fp-first"),
                SafeRedactor.hashValue("fp-second")), fingerprints(writtenLines));
        assertFalse(String.join("\n", writtenLines).contains(rawPrivateValue));
        assertEquals(0, store.ndjsonQueueSize());
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
