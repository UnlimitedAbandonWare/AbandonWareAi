package com.example.lms.debug;

import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.cfvm.CfvmJbCbCalculator;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.search.TraceStore;
import com.example.lms.test.SecretFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceHealthFailurePatternTraceBridgeTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordsSourceHealthPredictionIntoTraceDebugEventAndCfvmWithoutRawSecrets() {
        String rawSecret = SecretFixtures.openAiKey();
        DebugEventStore store = enabledDebugEventStore();
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider(null),
                provider(new CfvmJbCbCalculator()));
        SourceHealthFailurePatternTraceBridge bridge =
                new SourceHealthFailurePatternTraceBridge(store, provider(recorder));

        SourceHealthFailurePatternTraceBridge.RecordResult result = bridge.recordPrediction(
                Map.of(
                        "failurePatternKind", "cross_subsystem_concentration",
                        "patternId", "FP-S01S08-CROSS-CONCENTRATION",
                        "sourceRiskId", "cross_subsystem_concentration",
                        "riskScore", 0.84d,
                        "amplifiedSignalScore", 0.91d,
                        "traceStoreKeys", List.of(
                                "sourceHealth.failurePatternKind",
                                "harmony.score.S01_S05",
                                "ownerToken=" + rawSecret),
                        "amplifierTraceKeys", List.of(
                                "hypernova.twpmP",
                                "hypernova.cvarPhi",
                                "hypernova.riskKAlloc"),
                        "patchDropManifestHash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "rawPrompt", "Authorization Bearer " + rawSecret),
                "sourceHealthScorecard");

        assertTrue(result.debugEventEmitted());
        assertTrue(result.cfvmRecorded());
        assertEquals("cross_subsystem_concentration", TraceStore.get("sourceHealth.failurePatternKind"));
        assertEquals("FP-S01S08-CROSS-CONCENTRATION", TraceStore.get("sourceHealth.patternId"));
        assertEquals("cross_subsystem_concentration", TraceStore.get("sourceHealth.sourceRiskId"));
        assertEquals(0.91d, (Double) TraceStore.get("sourceHealth.amplifiedSignalScore"), 1.0e-9d);
        assertEquals(4.0d, (Double) TraceStore.get("hypernova.twpmP"), 1.0e-9d);
        assertEquals(0.91d, (Double) TraceStore.get("hypernova.cvarPhi"), 1.0e-9d);
        assertEquals(9, TraceStore.get("hypernova.riskKAlloc"));
        assertEquals(Boolean.TRUE, TraceStore.get("sourceHealth.cfvm.recorded"));
        assertEquals(1, buffer.size());

        DebugEvent event = store.list(5).stream()
                .filter(e -> e.probe() == DebugProbeType.TRACE_MEMORY)
                .findFirst()
                .orElseThrow();
        assertEquals(DebugEventLevel.WARN, event.level());
        assertEquals("source_health_failure_pattern", event.data().get("stage"));
        assertEquals("source_health.failure_pattern_prediction", event.data().get("failureClass"));
        assertEquals("cross_subsystem_concentration", event.data().get("failurePatternKind"));
        assertEquals("FP-S01S08-CROSS-CONCENTRATION", event.data().get("patternId"));
        assertEquals(Boolean.TRUE, event.data().get("cfvmRecorded"));

        String rendered = TraceStore.getAll() + "\n" + event;
        assertFalse(rendered.contains(rawSecret), rendered);
        assertFalse(rendered.contains("Authorization"), rendered);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains("rawPrompt"), rendered);
    }

    @Test
    void missingCfvmRecorderStillPromotesTraceAndDebugEventFailSoft() {
        DebugEventStore store = enabledDebugEventStore();
        SourceHealthFailurePatternTraceBridge bridge =
                new SourceHealthFailurePatternTraceBridge(store, provider(null));

        SourceHealthFailurePatternTraceBridge.RecordResult result = bridge.recordPrediction(
                Map.of(
                        "failurePatternKind", "aspect_order_hotspots",
                        "patternId", "FP-S01S08-ASPECT-ORDER",
                        "sourceRiskId", "aspect_order_hotspots",
                        "riskScore", 0.3d,
                        "amplifiedSignalScore", 0.42d),
                "sourceHealthScorecard");

        assertTrue(result.debugEventEmitted());
        assertFalse(result.cfvmRecorded());
        assertEquals(Boolean.FALSE, TraceStore.get("sourceHealth.cfvm.recorded"));
        assertEquals("cfvm_recorder_unavailable", TraceStore.get("sourceHealth.cfvm.skipReason"));
        assertEquals(1, store.list(5).size());
    }

    @Test
    void invalidScoreParseLeavesTraceBreadcrumbAndClampFlag() {
        DebugEventStore store = enabledDebugEventStore();
        SourceHealthFailurePatternTraceBridge bridge =
                new SourceHealthFailurePatternTraceBridge(store, provider(null));

        SourceHealthFailurePatternTraceBridge.RecordResult result = bridge.recordPrediction(
                Map.of(
                        "failurePatternKind", "loader_starvation",
                        "patternId", "FP-TRACE-MEMORY-LOADER-STARVATION",
                        "sourceRiskId", "loader_starvation",
                        "riskScore", "ownerToken=private-token",
                        "amplifiedSignalScore", "not-a-number"),
                "traceMemoryLoader");

        assertTrue(result.debugEventEmitted());
        assertFalse(result.cfvmRecorded());
        assertEquals(0.0d, (Double) TraceStore.get("sourceHealth.riskScore"), 1.0e-9d);
        assertEquals(0.0d, (Double) TraceStore.get("sourceHealth.amplifiedSignalScore"), 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("sourceHealth.scoreParseFallback"));
        assertEquals("NumberFormatException", TraceStore.get("sourceHealth.scoreParseFallback.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.clampApplied"));

        String rendered = TraceStore.getAll() + "\n" + store.list(5);
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("private-token"));
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new FixedObjectProvider<>(value);
    }

    private static final class FixedObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        private FixedObjectProvider(T value) {
            this.value = value;
        }

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

        @Override
        public Iterator<T> iterator() {
            return value == null ? Collections.emptyIterator() : List.of(value).iterator();
        }
    }
}
