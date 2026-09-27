package com.example.lms.debug.ai;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DebugAiMetricsCancellationDiagnosticsTest {

    private static final String RAW_PROMPT = "private cancellation prompt ownerToken=secret";
    private static final String RAW_MODEL = "private-model:timeout-probe";
    private static final String MALFORMED_VALUE = "not-a-boolean ownerToken=private-type-sentinel";

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void scorecardAggregatesAtMostTwentySnapshotCancellationOutcomeCounts() {
        TraceSnapshotStore snapshotStore = enabledSnapshotStore();
        for (int i = 0; i < 25; i++) {
            captureCancellation(snapshotStore, i % 2 == 0, i % 4 < 2);
        }
        TraceStore.clear();
        DebugAiMetricsService service = metricsService(snapshotStore);

        DebugAiMetricSnapshot snapshot = service.snapshot(10, 60_000L);
        Map<String, Object> scorecard = snapshot.scorecard();
        Map<?, ?> cancellation = (Map<?, ?>) scorecard.get("llmTimeoutCancellation");

        assertNotNull(cancellation);
        assertEquals("trace_snapshots", cancellation.get("source"));
        assertEquals(20L, number(cancellation, "snapshotRowsScanned"));
        assertEquals(20L, number(cancellation, "sampleCount"));
        assertEquals(20L, number(cancellation, "mayInterruptTrueCount"));
        assertEquals(20L, number(cancellation, "workerTerminationNotObservedCount"));
        assertEquals(5L, number(cancellation, "acceptedAndFutureCancelledCount"));
        assertEquals(5L, number(cancellation, "acceptedButFutureNotCancelledCount"));
        assertEquals(5L, number(cancellation, "notAcceptedButFutureCancelledCount"));
        assertEquals(5L, number(cancellation, "notAcceptedAndFutureNotCancelledCount"));
        assertEquals(0L, snapshot.totalEvents(), "count-only diagnostics must not create scored event slots");
        assertEquals(0L, snapshot.warnEvents());
        assertEquals(0L, snapshot.errorEvents());
        assertFalse((Boolean) scorecard.get("anomalyTriggered"));
        assertFalse(snapshot.tiles().stream().anyMatch(tile -> tile.eventCount() > 0L));
        String dump = snapshot.toString();
        assertFalse(dump.contains(RAW_PROMPT), dump);
        assertFalse(dump.contains(RAW_MODEL), dump);
    }

    @Test
    void currentCancellationOutcomeTakesPrecedenceOverSnapshotHistory() {
        TraceSnapshotStore snapshotStore = enabledSnapshotStore();
        captureCancellation(snapshotStore, false, false);
        TraceStore.clear();
        putCancellationTrace(true, false);
        DebugAiMetricsService service = metricsService(snapshotStore);

        Map<String, Object> scorecard = service.snapshot(10, 60_000L).scorecard();
        Map<?, ?> cancellation = (Map<?, ?>) scorecard.get("llmTimeoutCancellation");

        assertNotNull(cancellation);
        assertEquals("trace_current", cancellation.get("source"));
        assertEquals(0L, number(cancellation, "snapshotRowsScanned"));
        assertEquals(1L, number(cancellation, "sampleCount"));
        assertEquals(1L, number(cancellation, "acceptedButFutureNotCancelledCount"));
        assertEquals(0L, number(cancellation, "notAcceptedAndFutureNotCancelledCount"));
    }

    @Test
    void duplicateSnapshotsForSameTraceCountOnce() {
        TraceSnapshotStore snapshotStore = enabledSnapshotStore();
        putCancellationTrace(true, true);
        TraceStore.put("trace.id", "same-timeout-trace");
        assertNotNull(snapshotStore.captureCurrent(
                "chat.trace_html.final",
                "POST",
                "/api/chat",
                200,
                null));
        assertNotNull(snapshotStore.captureCurrent(
                "http_request",
                "POST",
                "/api/chat",
                200,
                null));
        TraceStore.clear();
        DebugAiMetricsService service = metricsService(snapshotStore);

        Map<?, ?> cancellation = (Map<?, ?>) service.snapshot(10, 60_000L)
                .scorecard()
                .get("llmTimeoutCancellation");

        assertNotNull(cancellation);
        assertEquals(2L, number(cancellation, "snapshotRowsScanned"));
        assertEquals(1L, number(cancellation, "sampleCount"));
        assertEquals(1L, number(cancellation, "duplicateRowsSkipped"));
        assertEquals(1L, number(cancellation, "acceptedAndFutureCancelledCount"));
    }

    @Test
    void malformedCancellationValuesAreCountedInvalidAndExcludedFromOutcomeBuckets() {
        DebugAiMetricSnapshot baseline = metricsService(enabledSnapshotStore()).snapshot(10, 60_000L);
        TraceStore.clear();

        TraceSnapshotStore snapshotStore = enabledSnapshotStore();
        captureCancellation(snapshotStore, true, true);
        TraceStore.put("llm.call.timeout", true);
        TraceStore.put("llm.call.timeout.cancelMayInterruptIfRunning", "true");
        TraceStore.put("llm.call.timeout.cancelAccepted", MALFORMED_VALUE);
        TraceStore.put("llm.call.timeout.futureCancelledState", "false");
        TraceStore.put("llm.call.timeout.rawPrompt", RAW_PROMPT);
        assertNotNull(snapshotStore.captureCurrent(
                "unit_test",
                "POST",
                "/api/chat",
                200,
                null));
        TraceStore.clear();

        DebugAiMetricSnapshot observed = metricsService(snapshotStore).snapshot(10, 60_000L);
        Map<?, ?> cancellation = (Map<?, ?>) observed.scorecard().get("llmTimeoutCancellation");

        assertNotNull(cancellation);
        assertEquals("trace_snapshots", cancellation.get("source"));
        assertEquals(2L, number(cancellation, "snapshotRowsScanned"));
        assertEquals(1L, number(cancellation, "sampleCount"));
        assertEquals(1L, number(cancellation, "invalidSampleCount"));
        assertEquals(1L, number(cancellation, "acceptedAndFutureCancelledCount"));
        assertEquals(0L, number(cancellation, "acceptedButFutureNotCancelledCount"));
        assertEquals(0L, number(cancellation, "notAcceptedButFutureCancelledCount"));
        assertEquals(0L, number(cancellation, "notAcceptedAndFutureNotCancelledCount"));
        long outcomeCount = number(cancellation, "acceptedAndFutureCancelledCount")
                + number(cancellation, "acceptedButFutureNotCancelledCount")
                + number(cancellation, "notAcceptedButFutureCancelledCount")
                + number(cancellation, "notAcceptedAndFutureNotCancelledCount");
        assertEquals(number(cancellation, "sampleCount"), outcomeCount);
        assertEquals(baseline.totalEvents(), observed.totalEvents());
        assertEquals(baseline.warnEvents(), observed.warnEvents());
        assertEquals(baseline.errorEvents(), observed.errorEvents());
        assertEquals(baseline.scorecard().get("anomalyTriggered"), observed.scorecard().get("anomalyTriggered"));
        assertEquals(baseline.scorecard().get("anomalyScore"), observed.scorecard().get("anomalyScore"));
        assertEquals(baseline.scorecard().get("virtualMatrixWeightedScore"),
                observed.scorecard().get("virtualMatrixWeightedScore"));
        assertEquals(baseline.recommendations(), observed.recommendations());
        String dump = observed.toString();
        assertFalse(dump.contains(MALFORMED_VALUE), dump);
        assertFalse(dump.contains(RAW_PROMPT), dump);
        assertFalse(dump.contains(RAW_MODEL), dump);
    }

    @Test
    void scorecardExposesCountOnlySnapshotRetentionFacts() {
        TraceSnapshotStore snapshotStore = enabledSnapshotStore();
        ReflectionTestUtils.setField(snapshotStore, "maxSize", 2);
        String[] snapshotIds = new String[3];
        for (int i = 0; i < snapshotIds.length; i++) {
            putCancellationTrace(true, true);
            snapshotIds[i] = snapshotStore.captureCurrent(
                    "unit_test",
                    "POST",
                    "/api/chat/private-retention-scorecard-" + i,
                    200,
                    null);
            assertNotNull(snapshotIds[i]);
            TraceStore.clear();
        }

        DebugAiMetricSnapshot observed = metricsService(snapshotStore).snapshot(10, 60_000L);
        Map<?, ?> retention = (Map<?, ?>) observed.scorecard().get("traceSnapshotRetention");

        assertNotNull(retention);
        assertEquals(Set.of(
                "storageMode",
                "captureEnabled",
                "restartDurable",
                "counterScope",
                "capacity",
                "retainedSnapshotCount",
                "capturedSnapshotCount",
                "evictedSnapshotCount"), retention.keySet());
        assertEquals("memory_only", retention.get("storageMode"));
        assertEquals(Boolean.TRUE, retention.get("captureEnabled"));
        assertEquals(Boolean.FALSE, retention.get("restartDurable"));
        assertEquals(2L, number(retention, "capacity"));
        assertEquals(2L, number(retention, "retainedSnapshotCount"));
        assertEquals(3L, number(retention, "capturedSnapshotCount"));
        assertEquals(1L, number(retention, "evictedSnapshotCount"));
        assertEquals(0L, observed.totalEvents());
        String dump = observed.toString();
        for (String id : snapshotIds) {
            assertFalse(dump.contains(id), dump);
        }
        assertFalse(dump.contains("private-retention-scorecard"), dump);
    }

    private static void captureCancellation(
            TraceSnapshotStore store,
            boolean cancelAccepted,
            boolean futureCancelledState) {
        putCancellationTrace(cancelAccepted, futureCancelledState);
        assertNotNull(store.captureCurrent(
                "unit_test",
                "POST",
                "/api/chat",
                200,
                null));
        TraceStore.clear();
    }

    private static void putCancellationTrace(boolean cancelAccepted, boolean futureCancelledState) {
        TraceStore.put("llm.call.timeout", true);
        TraceStore.put("llm.call.timeout.cancelMayInterruptIfRunning", true);
        TraceStore.put("llm.call.timeout.cancelAccepted", cancelAccepted);
        TraceStore.put("llm.call.timeout.futureCancelledState", futureCancelledState);
        TraceStore.put("llm.call.timeout.workerTerminationEvidence", "not_observed");
        TraceStore.put("llm.call.timeout.rawPrompt", RAW_PROMPT);
        TraceStore.put("llm.call.timeout.modelHash", "hash:private-model-timeout-probe");
    }

    private static DebugAiMetricsService metricsService(TraceSnapshotStore snapshotStore) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("traceSnapshotStore", snapshotStore);
        ObjectProvider<TraceSnapshotStore> provider = factory.getBeanProvider(TraceSnapshotStore.class);
        return new DebugAiMetricsService(new DebugEventStore(), provider);
    }

    private static TraceSnapshotStore enabledSnapshotStore() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> htmlProvider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(htmlProvider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 30);
        ReflectionTestUtils.setField(store, "maxValueLen", 1_000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 30);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "htmlEnabled", false);
        return store;
    }

    private static long number(Map<?, ?> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
