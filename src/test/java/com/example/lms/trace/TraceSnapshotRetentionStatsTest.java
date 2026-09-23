package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.service.trace.TraceHtmlBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotRetentionStatsTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void reportsProcessLocalCaptureAndEvictionWithoutIdentifiers() {
        TraceSnapshotStore store = enabledStore(2);
        List<String> snapshotIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = store.captureCustom(
                    "unit_test",
                    "POST",
                    "/api/chat/private-retention-probe-" + i,
                    200,
                    null,
                    Map.of("trace.id", "private-trace-" + i),
                    null);
            assertNotNull(id);
            snapshotIds.add(id);
        }

        Map<String, Object> stats = store.retentionStats();

        assertEquals(Set.of(
                "storageMode",
                "captureEnabled",
                "restartDurable",
                "counterScope",
                "capacity",
                "retainedSnapshotCount",
                "capturedSnapshotCount",
                "evictedSnapshotCount"), stats.keySet());
        assertEquals("memory_only", stats.get("storageMode"));
        assertEquals(Boolean.TRUE, stats.get("captureEnabled"));
        assertEquals(Boolean.FALSE, stats.get("restartDurable"));
        assertEquals("process_lifetime", stats.get("counterScope"));
        assertEquals(2L, number(stats, "capacity"));
        assertEquals(2L, number(stats, "retainedSnapshotCount"));
        assertEquals(3L, number(stats, "capturedSnapshotCount"));
        assertEquals(1L, number(stats, "evictedSnapshotCount"));

        String dump = stats.toString();
        snapshotIds.forEach(id -> assertFalse(dump.contains(id), dump));
        assertFalse(dump.contains("private-retention-probe"), dump);
        assertFalse(dump.contains("private-trace"), dump);
    }

    @Test
    @SuppressWarnings("unchecked")
    void capacityPlusOneEvictsOnlyTheOldestCaptureBudget() {
        TraceSnapshotStore store = enabledStore(1);
        ReflectionTestUtils.setField(store, "maxPerTrace", 1);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);

        for (int i = 0; i <= 8_192; i++) {
            boolean admitted = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                    store,
                    "consumeBudget",
                    "trace-budget-" + i,
                    1_000L + i));
            assertTrue(admitted, "distinct budget key " + i + " must be admitted");
        }

        Map<String, ?> budgets = (Map<String, ?>) ReflectionTestUtils.getField(store, "budgets");
        assertNotNull(budgets);
        assertEquals(8_192, budgets.size());
        assertFalse(budgets.containsKey("trace-budget-0"));
        for (int i = 1; i <= 8_192; i++) {
            assertTrue(budgets.containsKey("trace-budget-" + i), "newer budget key was lost: " + i);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentBudgetInsertionRemainsBoundedAndRetainsNewAdmissions() throws Exception {
        TraceSnapshotStore store = enabledStore(1);
        ReflectionTestUtils.setField(store, "maxPerTrace", 1);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        for (int i = 0; i < 8_192; i++) {
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                    store,
                    "consumeBudget",
                    "older-budget-" + i,
                    2_000L + i)));
        }

        int concurrentInsertions = 32;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrentInsertions; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                            store,
                            "consumeBudget",
                            "new-budget-" + index,
                            20_000L + index));
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        Map<String, ?> budgets = (Map<String, ?>) ReflectionTestUtils.getField(store, "budgets");
        assertNotNull(budgets);
        assertEquals(8_192, budgets.size());
        for (int i = 0; i < concurrentInsertions; i++) {
            assertTrue(budgets.containsKey("new-budget-" + i), "new admission was globally cleared: " + i);
        }
        assertFalse(store.retentionStats().toString().contains("new-budget-"));
    }

    private static TraceSnapshotStore enabledStore(int maxSize) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> htmlProvider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(htmlProvider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", maxSize);
        ReflectionTestUtils.setField(store, "maxValueLen", 1_000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "htmlEnabled", false);
        return store;
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
