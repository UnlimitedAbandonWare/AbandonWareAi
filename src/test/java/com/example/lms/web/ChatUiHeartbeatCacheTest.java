package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatUiHeartbeatCacheTest {

    @Test
    void concurrentRequestsShareOneSnapshotUntilTheTtlBoundary() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000L);
        AtomicInteger refreshes = new AtomicInteger();
        ChatUiHeartbeatCache cache = new ChatUiHeartbeatCache(now::get, Duration.ofSeconds(30));
        Supplier<Map<String, Object>> refresh = () -> {
            refreshes.incrementAndGet();
            return Map.of(
                    "statusBand", "OK",
                    "reasonCode", "ready",
                    "nextAction", "none",
                    "ageMs", 0L);
        };

        int callers = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return cache.getOrRefresh(refresh);
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

        assertEquals(1, refreshes.get());

        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertEquals(29_000L, cache.getOrRefresh(refresh).get("ageMs"));
        assertEquals(1, refreshes.get());

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(0L, cache.getOrRefresh(refresh).get("ageMs"));
        assertEquals(2, refreshes.get());
    }

    @Test
    void expiredSnapshotIsReplacedByTheNewImmutableProjection() {
        AtomicLong now = new AtomicLong(5_000_000L);
        AtomicInteger refreshes = new AtomicInteger();
        ChatUiHeartbeatCache cache = new ChatUiHeartbeatCache(now::get, Duration.ofSeconds(30));
        Supplier<Map<String, Object>> refresh = () -> refreshes.incrementAndGet() == 1
                ? Map.of(
                        "statusBand", "OK",
                        "reasonCode", "ready",
                        "nextAction", "none",
                        "ageMs", 0L)
                : Map.of(
                        "statusBand", "WARN",
                        "reasonCode", "heartbeat_unavailable",
                        "nextAction", "retry_heartbeat",
                        "ageMs", 0L);

        assertEquals("OK", cache.getOrRefresh(refresh).get("statusBand"));

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        Map<String, Object> unavailable = cache.getOrRefresh(refresh);
        assertEquals(Map.of(
                "statusBand", "WARN",
                "reasonCode", "heartbeat_unavailable",
                "nextAction", "retry_heartbeat",
                "ageMs", 0L), unavailable);
        assertThrows(UnsupportedOperationException.class, () -> unavailable.put("statusBand", "OK"));
        assertEquals(2, refreshes.get());

        assertEquals(unavailable, cache.getOrRefresh(refresh));
        assertEquals(2, refreshes.get());
    }
}
