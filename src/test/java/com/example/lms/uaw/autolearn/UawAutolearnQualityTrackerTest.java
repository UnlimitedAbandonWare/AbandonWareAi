package com.example.lms.uaw.autolearn;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawAutolearnQualityTrackerTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void projectsSpikeAndQuarantineWithoutRawContent() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(8);
        props.getValidation().setEwmaAlpha(0.5d);
        UawAutolearnQualityTracker tracker = new UawAutolearnQualityTracker(props, null, null);

        LearningSampleValidationMetadata good = accepted(0.82d, 0.0d, 0.0d);
        tracker.recordSample(good, "s1", "hash1", false, true, true);
        tracker.recordSample(good, "s1", "hash2", false, true, true);

        LearningSampleValidationMetadata contaminated = accepted(0.78d, 0.80d, 0.0d);
        LearningSampleValidationMetadata projected = tracker.project(contaminated, false, true);

        assertTrue(projected.anomalies().flags().contains("context_contamination_threshold"));
        assertTrue(projected.anomalies().flags().contains("spike"));
        assertTrue(projected.anomalies().spike());
        assertEquals("QUARANTINE", projected.feedback().vectorDecision());
        assertEquals(projected.anomalies().flags(), TraceStore.get("learning.anomaly.flags"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "vector_quarantine".equals(row.get("label"))));
    }

    @Test
    void tracksBoundedErrorRateWindow() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(4);
        UawAutolearnQualityTracker tracker = new UawAutolearnQualityTracker(props, null, null);

        tracker.recordSample(accepted(0.82d, 0.0d, 0.0d), "s1", "hash1", false, true, true);
        tracker.recordExternalError("ingest_fail");
        tracker.recordExternalError("vector_upsert_fail");
        tracker.recordExternalError("writer_failed");
        tracker.recordExternalError("empty_result");

        assertEquals(4L, ((Number) TraceStore.get("learning.metrics.windowSize")).longValue());
        assertEquals(1.0d, ((Number) TraceStore.get("learning.metrics.errorRateWindow")).doubleValue(), 0.0001d);
        assertTrue(((Number) TraceStore.get("learning.threshold.tuningDelta")).doubleValue() > 0.0d);
    }

    @Test
    void externalErrorReasonDoesNotExposeRawSecrets() {
        DebugEventStore debugEventStore = enabledDebugEventStore();
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, provider(debugEventStore));
        String secret = "sk-" + "uawautolearnsecret1234567890";

        tracker.recordExternalError("provider_down api_key=" + secret);

        DebugEvent event = debugEventStore.list(10).stream()
                .filter(ev -> "UawAutolearnQualityTracker.recordExternalError".equals(ev.where()))
                .findFirst()
                .orElseThrow();
        String rendered = TraceStore.getString("learning.metrics.externalError") + " " + event.data();
        assertFalse(rendered.contains(secret));
        assertFalse(rendered.contains("api_key_" + secret));
    }

    @Test
    void externalErrorFreeFormReasonDoesNotLeakIntoTraceReasonCounts() {
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, null);
        String privateReason = "private student query next appointment";

        tracker.recordExternalError(privateReason);
        tracker.finishCycle("s1", 1, 0, false, "C:\\secret\\train_rag.jsonl");

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(privateReason), trace);
        assertFalse(trace.contains("private_student"), trace);
        assertFalse(trace.contains("next_appointment"), trace);
        assertTrue(String.valueOf(TraceStore.get("learning.metrics.externalError")).startsWith("hash:"), trace);
        assertTrue(String.valueOf(TraceStore.get("learning.cycle.topProblem")).startsWith("hash:"), trace);
    }

    @Test
    void finishCycleBlocksRetrainWhenRollingErrorRateIsTooHigh() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(4);
        props.getValidation().setMaxTrainErrorRate(0.35d);
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(props, null, null, provider(blackboxService(true)));

        tracker.recordExternalError("empty_result");
        tracker.recordExternalError("writer_failed");
        tracker.recordSample(accepted(0.82d, 0.0d, 0.0d), "s1", "hash1", false, true, true);

        String datasetPath = "C:\\secret\\train_rag.jsonl";
        UawAutolearnQualityTracker.CycleDiagnostics cycle =
                tracker.finishCycle("s1", 3, 1, false, datasetPath);

        assertFalse(cycle.trainAllowed());
        assertEquals("BLOCK_RETRAIN", cycle.trainDecision());
        assertTrue(cycle.errorRateWindow() > cycle.maxTrainErrorRate());
        assertTrue(cycle.flags().contains("error_rate_threshold"));
        assertTrue(cycle.reasonCounts().containsKey("empty_result"));
        assertEquals("BLOCK_RETRAIN", TraceStore.getString("learning.cycle.trainDecision"));
        assertFalse((Boolean) TraceStore.get("learning.training.allowed"));
        assertTrue(TraceStore.get("uaw.idle.loop-diagnostics") instanceof Map<?, ?>);
        assertTrue(TraceStore.get("uaw.autolearn.loop.hotspot") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) TraceStore.get("uaw.idle.loop-diagnostics");
        assertFalse(diagnostics.containsKey("sessionId"));
        assertFalse(diagnostics.containsKey("datasetPath"));
        assertEquals(Boolean.TRUE, diagnostics.get("hasSessionId"));
        assertEquals(SafeRedactor.hashValue("s1"), diagnostics.get("sessionHash"));
        assertFalse(diagnostics.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), diagnostics.get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), diagnostics.get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(datasetPath), diagnostics.get("datasetPathHash"));
        assertFalse(String.valueOf(diagnostics).contains("C:\\secret"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hotspot = (Map<String, Object>) TraceStore.get("uaw.autolearn.loop.hotspot");
        assertEquals("BLOCK_RETRAIN", hotspot.get("trainDecision"));
        assertTrue(tracker.lastLoopDiagnostics().containsKey("thresholdBreaks"));
        assertTrue(tracker.lastLoopDiagnostics().containsKey("vectorDecision"));
        @SuppressWarnings("unchecked")
        Map<String, Object> blackbox = (Map<String, Object>) diagnostics.get("blackbox");
        @SuppressWarnings("unchecked")
        Map<String, Object> matrix = (Map<String, Object>) blackbox.get("matrix");
        assertEquals("rag-matrix-v2", matrix.get("matrix.schemaVersion"));
    }

    @Test
    void keepsLastLoopSnapshotsAfterTraceStoreClears() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(4);
        props.getValidation().setMaxTrainErrorRate(0.35d);
        UawAutolearnQualityTracker tracker = new UawAutolearnQualityTracker(props, null, null);

        tracker.recordExternalError("writer_failed");
        tracker.recordExternalError("empty_result");
        tracker.finishCycle("s1", 2, 0, false, "C:\\secret\\train_rag.jsonl");

        assertEquals("BLOCK_RETRAIN", tracker.lastLoopDiagnostics().get("trainDecision"));
        assertFalse(tracker.lastLoopDiagnostics().containsKey("sessionId"));
        assertFalse(tracker.lastLoopDiagnostics().containsKey("datasetPath"));
        assertEquals(SafeRedactor.hashValue("s1"), tracker.lastLoopDiagnostics().get("sessionHash"));
        assertEquals("threshold", tracker.lastLoopHotspot().get("hotspot"));

        TraceStore.clear();

        assertEquals("BLOCK_RETRAIN", tracker.lastLoopDiagnostics().get("trainDecision"));
        assertEquals("threshold", tracker.lastLoopHotspot().get("hotspot"));
    }

    @Test
    void recordsLowCardinalityHotspotForExternalErrorsAndSampleMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), provider(registry), null);

        tracker.recordExternalError("provider_disabled");
        assertEquals("provider", TraceStore.getString("learning.error.hotspot"));
        assertNotNull(registry.find("uaw.autolearn.validation.external_error_total")
                .tag("hotspot", "provider")
                .counter());

        LearningSampleValidationMetadata projected =
                tracker.project(accepted(0.78d, 0.80d, 0.0d), false, true);
        tracker.recordSample(projected, "s1", "hash-contaminated", false, true, true);

        assertEquals("context_contamination", TraceStore.getString("learning.error.hotspot"));
        assertTrue(TraceStore.get("learning.validation.thresholdBreaks") instanceof List<?>);
        assertNotNull(registry.find("uaw.autolearn.validation.sample_total")
                .tag("hotspot", "context_contamination")
                .counter());

        LearningSampleValidationMetadata contradiction =
                tracker.project(contradictionRejected(), false, true);
        tracker.recordSample(contradiction, "s1", "hash-contradiction", false, true, true);

        assertEquals("evidence_gate", TraceStore.getString("learning.error.hotspot"));
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) TraceStore.get("learning.validation.contaminationSignals");
        assertTrue(signals.contains("evidence_conflict"));
        assertNotNull(registry.find("uaw.autolearn.validation.sample_total")
                .tag("hotspot", "evidence_gate")
                .counter());
    }

    @Test
    void usesDynamicContradictionThresholdAndInclusiveBreak() {
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, null);
        LearningSampleValidationMetadata borderline = new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.30d,
                0.78d,
                0.55d,
                "evidence_conflict",
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.72d,
                List.of(),
                List.of("cause_effect_support"),
                new LearningSampleValidationMetadata.Thresholds(0.55d, 0.35d, 0.40d, 0.55d, 0.0d, "dynamic"),
                new LearningSampleValidationMetadata.Runtime(3, 3, 0.80d, 1.0d, 0.0d),
                LearningSampleValidationMetadata.Anomalies.none(),
                LearningSampleValidationMetadata.Feedback.none());

        LearningSampleValidationMetadata projected = tracker.project(borderline, false, true);
        tracker.recordSample(projected, "s1", "hash-borderline", false, true, true);

        assertTrue(projected.anomalies().flags().contains("contradiction_risk"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row ->
                "contradiction_risk".equals(row.get("label"))
                        && ((Number) row.get("value")).doubleValue() == 0.55d
                        && ((Number) row.get("threshold")).doubleValue() == 0.55d));
    }

    @Test
    void highRiskBlackboxSampleStaysQuarantinedAndVisibleInLoopSummary() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(4);
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(props, null, null, provider(blackboxService(true)));
        TraceStore.put("web.naver.providerDisabled", true);

        LearningSampleValidationMetadata projected = tracker.project(accepted(0.82d, 0.0d, 0.0d), false, true);
        tracker.recordSample(projected, "raw-session-id", "sample-hash", false, true, true);
        tracker.finishCycle("raw-session-id", 1, 1, false, "C:\\secret\\train_rag.jsonl");

        assertTrue(projected.anomalies().flags().contains("blackbox_provider_disabled"));
        assertEquals("QUARANTINE", projected.feedback().vectorDecision());
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) TraceStore.get("uaw.idle.loop-diagnostics");
        @SuppressWarnings("unchecked")
        Map<String, Object> blackbox = (Map<String, Object>) diagnostics.get("blackbox");
        assertEquals("provider_disabled", blackbox.get("dominantFailure"));
        assertEquals("disable_provider_failsoft", blackbox.get("restoreAction"));
        @SuppressWarnings("unchecked")
        Map<String, Object> matrix = (Map<String, Object>) blackbox.get("matrix");
        assertEquals("rag-matrix-v2", matrix.get("matrix.schemaVersion"));
        assertTrue(((Number) matrix.get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(matrix.containsKey("q_overall_health"));
        assertFalse(matrix.containsKey("_order"));
        assertFalse(String.valueOf(diagnostics).contains("raw-session-id"));
        assertFalse(String.valueOf(diagnostics).contains("C:\\secret"));
    }

    @Test
    void nonFiniteBlackboxRiskTraceDoesNotCreateHighRiskAnomaly() {
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, null);
        TraceStore.put("blackbox.risk.riskScore", "Infinity");
        TraceStore.put("blackbox.risk.priorityScore", 0.0d);
        TraceStore.put("blackbox.risk.dominantFailure", "provider_disabled");

        LearningSampleValidationMetadata projected = tracker.project(accepted(0.82d, 0.0d, 0.0d), false, true);

        assertFalse(projected.anomalies().flags().stream().anyMatch(flag -> flag.startsWith("blackbox_")));
        assertEquals("blackbox.risk.riskScore",
                TraceStore.get("uaw.autolearn.quality.suppressed.traceDouble"));
    }

    @Test
    void mergeGpuGatewayBlockedRefreshesBlackboxHotspotAndLoopDiagnostics() {
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, null, provider(blackboxService(true)));
        Map<String, Object> values = Map.of(
                "reason", "gpu_gateway_unreachable",
                "trainDecision", "BLOCK_HEAVY_WORK",
                "diagnosis", "desktop_gpu_gateway_unreachable",
                "ownerToken", "owner-token-must-not-leak",
                "gpuGatewayPreflight", Map.of(
                        "status", "unreachable",
                        "configuredCount", 3,
                        "reachableCount", 0));

        tracker.mergeLastLoopDiagnostics("gpu_gateway_blocked", values);

        assertTrue(tracker.lastLoopDiagnostics().containsKey("gpu_gateway_blocked"));
        assertFalse(String.valueOf(tracker.lastLoopDiagnostics()).contains("owner-token-must-not-leak"));
        assertEquals("provider", tracker.lastLoopHotspot().get("hotspot"));
        assertEquals("gpu_gateway_unreachable", tracker.lastLoopHotspot().get("topProblem"));
        assertEquals("gpu_gateway_unreachable", TraceStore.get("blackbox.risk.dominantFailure"));
        assertEquals("cooldown_reorder", TraceStore.get("blackbox.risk.restoreAction"));
        @SuppressWarnings("unchecked")
        Map<String, Object> matrix = (Map<String, Object>) TraceStore.get("blackbox.risk.matrix");
        assertEquals(1.0d, ((Number) matrix.get("q_gpu_gateway_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) matrix.get("q_failsoft_pressure")).doubleValue(), 0.0001d);
    }

    @Test
    void previousVectorQuarantineDecisionDoesNotSelfContaminateNextSample() {
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, null, provider(blackboxService(true)));
        TraceStore.put("web.naver.providerDisabled", true);
        LearningSampleValidationMetadata first = tracker.project(accepted(0.82d, 0.0d, 0.0d), false, true);
        assertEquals("QUARANTINE", first.feedback().vectorDecision());

        TraceStore.put("web.naver.providerDisabled", null);
        TraceStore.put("learning.feedback.vectorDecision", "QUARANTINE");
        TraceStore.put("learning.validation.contaminationSignals", List.of("quarantine_vector_decision"));

        LearningSampleValidationMetadata projected = tracker.project(accepted(0.82d, 0.0d, 0.0d), false, true);

        assertFalse(projected.anomalies().flags().contains("blackbox_context_contamination"));
        assertFalse(projected.anomalies().flags().stream().anyMatch(flag -> flag.startsWith("blackbox_")));
        assertEquals("SHADOW_REVIEW", projected.feedback().vectorDecision());
        @SuppressWarnings("unchecked")
        Map<String, Object> matrix = (Map<String, Object>) TraceStore.get("blackbox.risk.matrix");
        assertEquals(0.0d, ((Number) matrix.get("q_learning_promotion_risk")).doubleValue(), 0.0001d);
    }

    @Test
    void recordSampleAndCycleEmitQuantitativeDebugEventsWithoutRawIds() {
        DebugEventStore debugEventStore = enabledDebugEventStore();
        UawAutolearnQualityTracker tracker =
                new UawAutolearnQualityTracker(new UawAutolearnProperties(), null, provider(debugEventStore));
        String datasetPath = "C:\\secret\\train_rag.jsonl";

        LearningSampleValidationMetadata projected =
                tracker.project(accepted(0.82d, 0.0d, 0.0d), false, true);
        tracker.recordSample(projected, "raw-session-id", "sample-hash", false, true, true);
        tracker.finishCycle("raw-session-id", 1, 1, false, datasetPath);

        DebugEvent sample = debugEventStore.list(10).stream()
                .filter(ev -> "UawAutolearnQualityTracker.recordSample".equals(ev.where()))
                .findFirst()
                .orElseThrow();
        assertEquals(DebugProbeType.AUTOLEARN, sample.probe());
        assertEquals(3, sample.data().get("evidenceCount"));
        assertEquals(3, sample.data().get("afterFilterCount"));
        assertEquals(0.80d, ((Number) sample.data().get("contextDiversity")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) sample.data().get("laneCoverage")).doubleValue(), 0.0001d);
        assertEquals(SafeRedactor.hashValue("raw-session-id"), sample.data().get("sessionHash"));
        assertFalse(sample.data().containsKey("sessionId"));

        DebugEvent cycle = debugEventStore.list(10).stream()
                .filter(ev -> "UawAutolearnQualityTracker.finishCycle".equals(ev.where()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, cycle.data().get("attempted"));
        assertEquals(1, cycle.data().get("accepted"));
        assertEquals(1.0d, ((Number) cycle.data().get("acceptanceRate")).doubleValue(), 0.0001d);
        assertEquals(SafeRedactor.hashValue("raw-session-id"), cycle.data().get("sessionHash"));
        assertFalse(cycle.data().containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), cycle.data().get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), cycle.data().get("datasetFileLength"));
        assertEquals(SafeRedactor.hashValue(datasetPath), cycle.data().get("datasetPathHash"));

        String dump = debugEventStore.list(10).toString();
        assertFalse(dump.contains("raw-session-id"));
        assertFalse(dump.contains("C:\\secret"));
    }

    private static LearningSampleValidationMetadata accepted(double sampleScore,
                                                            double contamination,
                                                            double legacy) {
        return new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.30d,
                0.78d,
                new LearningSampleValidationMetadata.Requery(true, true),
                contamination,
                legacy,
                sampleScore,
                List.of(),
                List.of("cause_effect_support"),
                new LearningSampleValidationMetadata.Thresholds(0.55d, 0.35d, 0.40d, 0.0d, "dynamic"),
                new LearningSampleValidationMetadata.Runtime(3, 3, 0.80d, 1.0d, 0.0d),
                LearningSampleValidationMetadata.Anomalies.none(),
                LearningSampleValidationMetadata.Feedback.none());
    }

    private static LearningSampleValidationMetadata contradictionRejected() {
        return new LearningSampleValidationMetadata(
                "causal",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.30d,
                0.78d,
                0.78d,
                "evidence_conflict",
                new LearningSampleValidationMetadata.Requery(true, true),
                0.0d,
                0.0d,
                0.72d,
                List.of("contradiction_risk"),
                List.of("cause_effect_support"),
                new LearningSampleValidationMetadata.Thresholds(0.55d, 0.35d, 0.40d, 0.0d, "dynamic"),
                new LearningSampleValidationMetadata.Runtime(3, 3, 0.80d, 1.0d, 0.0d),
                LearningSampleValidationMetadata.Anomalies.none(),
                LearningSampleValidationMetadata.Feedback.none());
    }

    private static RagFailureBlackboxService blackboxService(boolean enabled) {
        RagFailureBlackboxService service = new RagFailureBlackboxService(null, null, null);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore debugEventStore = new DebugEventStore();
        ReflectionTestUtils.setField(debugEventStore, "enabled", true);
        ReflectionTestUtils.setField(debugEventStore, "maxSize", 20);
        ReflectionTestUtils.setField(debugEventStore, "windowMs", 60_000L);
        ReflectionTestUtils.setField(debugEventStore, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(debugEventStore, "flushIntervalMs", 15_000L);
        return debugEventStore;
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

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
