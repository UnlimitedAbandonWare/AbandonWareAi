package com.example.lms.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchObservationContextTest {
    @AfterEach void cleanup() { TraceStore.clear(); }

    @Test void overlappingCallbacksKeepInvocationIdsWhileLegacyWritesRemainShared() throws Exception {
        Map<String,Object> parent = TraceStore.context();
        Map<String,Object> a = TraceStore.searchContext(parent, "searchExecutionId");
        Map<String,Object> b = TraceStore.searchContext(parent, "searchExecutionId");
        var workers = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (var entry : Map.of(a.get("searchExecutionId"), a, b.get("searchExecutionId"), b).entrySet()) {
                futures.add(workers.submit(() -> {
                    TraceStore.installContext(entry.getValue());
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        TraceStore.withSearchContext("providerAttemptId", () -> {
                            var row = new HashMap<>(TraceStore.searchCorrelation(TraceStore.context()));
                            row.put("rawCount", entry.getKey().equals(a.get("searchExecutionId")) ? 1 : 2);
                            TraceStore.append("web.naver.filter.runs", row);
                            assertEquals(entry.getKey(), TraceStore.get("searchExecutionId"));
                            return null;
                        });
                        assertNull(TraceStore.get("providerAttemptId"));
                        TraceStore.put("legacy.count", 7);
                    } catch (Exception ex) { throw new AssertionError(ex); }
                    finally { TraceStore.clear(); }
                }));
            }
            for (var future : futures) future.get(6, TimeUnit.SECONDS);
            assertNull(parent.get("searchExecutionId"));
            assertEquals(7, parent.get("legacy.count"));
            var rows = (List<?>) parent.get("web.naver.filter.runs");
            assertEquals(2, rows.size());
            for (Object item : rows) {
                var row = (Map<?, ?>) item;
                assertEquals(row.get("searchExecutionId").equals(a.get("searchExecutionId")) ? 1 : 2, row.get("rawCount"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void boundedRowsAreImmutableAndEarlierSnapshotDoesNotChange() {
        var original = new HashMap<String,Object>(Map.of("rawCount", 1));
        TraceStore.append("web.naver.filter.runs", original);
        Object before = TraceStore.get("web.naver.filter.runs");
        original.put("rawCount", 99);
        for (int n=0; n<140; n++) TraceStore.append("web.naver.filter.runs", Map.of("rawCount", n));
        assertEquals(128, ((List<?>)TraceStore.get("web.naver.filter.runs")).size());
        assertEquals(13, TraceStore.getLong("web.naver.filter.runs.dropped"));
        assertEquals(1, ((List<?>)before).size());
        assertEquals(1, ((Map<?, ?>)((List<?>)before).get(0)).get("rawCount"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>)before).clear());
    }
}
