package com.example.lms.plugin.image.debug;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageJobDebugLedgerTest {

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void recordStoresRedactedSignalAndDebugEvent() {
        DebugEventStore store = enabledDebugEventStore();
        ImageJobDebugLedger ledger = enabledLedger(store);
        String jobId = "job-raw-123";
        String rawPrompt = "paint a private diagram with " + com.example.lms.test.SecretFixtures.openAiKey();
        String rawPath = "C:\\Users\\nninn\\Pictures\\private-output.png";

        ImageJobDebugSignal signal = ledger.record(jobId, ImageJobDebugAgent.CONFIG_SENTINEL,
                "config.disabled", 0.90, 1.0, 0.0, "FEATURE_DISABLED",
                Map.of(
                        "prompt", rawPrompt,
                        "absolutePath", rawPath,
                        "ownerToken", "owner-token-value",
                        "relayDelayMs", 300_000L));

        assertNotNull(signal);
        assertEquals(SafeRedactor.hashValue(jobId), signal.jobIdHash());
        assertEquals(ImageJobDebugAgent.CONFIG_SENTINEL, signal.agent());
        assertTrue(signal.triggered());
        assertTrue(signal.negativeScore() >= 0.55, "high-severity disabled config should trigger");

        Map<String, Object> snapshot = ledger.snapshot(jobId);
        assertEquals(SafeRedactor.hashValue(jobId), snapshot.get("jobIdHash"));
        assertEquals(1, snapshot.get("signalCount"));
        assertEquals(true, snapshot.get("triggered"));
        assertTrue(String.valueOf(snapshot.get("nextAction")).contains("config"));

        DebugEvent event = store.list(1).get(0);
        String dump = signal + "\n" + snapshot + "\n" + event;
        assertFalse(dump.contains(jobId));
        assertFalse(dump.contains(rawPrompt));
        assertFalse(dump.contains(rawPath));
        assertFalse(dump.contains("owner-token-value"));
        assertFalse(dump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
        assertTrue(dump.contains("hash:"));
    }

    @Test
    void suspicionUsesTailWeightedNegativeSignals() {
        ImageJobDebugLedger ledger = enabledLedger(enabledDebugEventStore());
        String jobId = "job-tail-risk";

        ledger.record(jobId, ImageJobDebugAgent.QUEUE_TIME,
                "queue.wait", 0.10, 300_000.0, 320_000.0, "OK", Map.of("sample", 1));
        ledger.record(jobId, ImageJobDebugAgent.PROVIDER,
                "provider.empty", 0.85, 1.0, 0.0, "NO_RESULT", Map.of("returnedCount", 0));
        ledger.record(jobId, ImageJobDebugAgent.ACCESS,
                "status.access.denied", 0.95, 1.0, 0.0, "SESSION_MISMATCH", Map.of("adminTokenPresented", false));

        Map<String, Object> snapshot = ledger.snapshot(jobId);

        assertEquals(3, snapshot.get("signalCount"));
        assertEquals(true, snapshot.get("triggered"));
        assertTrue(((Number) snapshot.get("suspicion")).doubleValue() >= 0.55);
        assertTrue(snapshot.get("topAgents").toString().contains("ACCESS"));
        assertTrue(snapshot.get("topAgents").toString().contains("PROVIDER"));
    }

    @Test
    void attemptScoreRecordsVerdictWithoutRawAttemptDetails() {
        DebugEventStore store = enabledDebugEventStore();
        ImageJobDebugLedger ledger = enabledLedger(store);
        String jobId = "job-score-private";
        String rawAttempt = "attempt-" + com.example.lms.test.SecretFixtures.openAiKey();
        String rawChange = "relay-delay C:\\Users\\nninn\\private\\image-output.png";
        String rawNote = "try lower relay delay with " + com.example.lms.test.SecretFixtures.openAiKey();

        Map<String, Object> result = ledger.recordAttemptScore(
                jobId, rawAttempt, rawChange, 0.20d, 0.70d, 0.10d, rawNote);

        assertEquals("PROMOTE", result.get("verdict"));
        assertEquals(0.40d, ((Number) result.get("reward")).doubleValue(), 1.0e-9);
        assertEquals(true, result.get("triggered"));
        assertEquals(SafeRedactor.hashValue(jobId), result.get("jobIdHash"));

        Map<String, Object> snapshot = ledger.snapshot(jobId);
        assertEquals(1, snapshot.get("signalCount"));
        assertTrue(String.valueOf(snapshot.get("lastSignals")).contains("attempt.score"));
        assertTrue(String.valueOf(snapshot.get("lastSignals")).contains("PROMOTE"));

        DebugEvent event = store.list(1).get(0);
        String dump = result + "\n" + snapshot + "\n" + event;
        assertFalse(dump.contains(jobId));
        assertFalse(dump.contains(rawAttempt));
        assertFalse(dump.contains(rawChange));
        assertFalse(dump.contains(rawNote));
        assertFalse(dump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
        assertTrue(dump.contains("hash:"));
    }

    @Test
    void invalidNumericDebugMetricsLeaveRedactedTraceBreadcrumb() {
        DebugEventStore store = enabledDebugEventStore();
        ImageJobDebugLedger ledger = enabledLedger(store);
        String rawMetric = "bad-number-" + com.example.lms.test.SecretFixtures.openAiKey();

        ImageJobDebugSignal signal = ledger.record("job-parse-private", ImageJobDebugAgent.UI_POLL,
                "ui.wait", 0.10, 1.0, 1.0, "QUEUED",
                Map.of("waitingMs", rawMetric, "expectedUiPollMs", 1000));

        assertNotNull(signal);
        assertEquals(Boolean.TRUE, TraceStore.get("image.job.debug.metricCoercion.failed"));
        assertEquals("waitingMs", TraceStore.get("image.job.debug.metricCoercion.field"));
        assertEquals("invalid_number", TraceStore.get("image.job.debug.metricCoercion.errorType"));
        assertEquals(SafeRedactor.hashValue(rawMetric),
                TraceStore.get("image.job.debug.metricCoercion.valueHash"));
        assertEquals(rawMetric.length(), TraceStore.get("image.job.debug.metricCoercion.valueLength"));

        String traceDump = TraceStore.getAll().toString();
        assertFalse(traceDump.contains(rawMetric));
        assertFalse(traceDump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    private static ImageJobDebugLedger enabledLedger(DebugEventStore store) {
        ImageJobDebugLedger ledger = new ImageJobDebugLedger(provider(store));
        ReflectionTestUtils.setField(ledger, "enabled", true);
        ReflectionTestUtils.setField(ledger, "maxSignals", 20);
        ReflectionTestUtils.setField(ledger, "sessionTtlMs", 32_400_000L);
        ReflectionTestUtils.setField(ledger, "deltaThreshold", 0.35);
        ReflectionTestUtils.setField(ledger, "negativeThreshold", 0.55);
        return ledger;
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        return store;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
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
        };
    }
}
