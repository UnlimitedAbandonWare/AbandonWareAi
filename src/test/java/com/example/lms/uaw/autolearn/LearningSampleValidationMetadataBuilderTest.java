package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningSampleValidationMetadataBuilderTest {

    private final LearningSampleValidationMetadataBuilder builder = new LearningSampleValidationMetadataBuilder();

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void buildsCausalLongTailMetadataFromSelfAskLanes() {
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
        TraceStore.put("selfask.3way.requery.confirmed", true);

        LearningSampleValidationMetadata meta = builder.build(
                "Why does cache invalidation cause stale search answers in a RAG service?",
                "The answer is supported by cited retrieval evidence.",
                "gemma4:26b",
                4,
                4,
                true,
                0.80d,
                "");

        assertEquals("causal", meta.questionType());
        assertEquals(3, meta.selfAskLanes().size());
        assertEquals(1.0d, meta.selfAskLaneCoverage(), 0.0001d);
        assertTrue(meta.requery().required());
        assertTrue(meta.requery().confirmed());
        assertTrue(meta.accepted());
        assertTrue(meta.evaluationCriteria().contains("cause_effect_support"));
        assertEquals("accepted", TraceStore.getString("learning.validation.decision"));
    }

    @Test
    void promotesNeedleRoiOnlyAfterThreeWayRequeryConfirmation() {
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
        TraceStore.put("selfask.3way.requery.confirmed", true);
        TraceStore.put("needle.triggered", true);
        TraceStore.put("needle.docs.count", 3);
        TraceStore.put("needle.urls.count", 2);
        TraceStore.put("needle.quality.authorityAvg", 0.92d);
        TraceStore.put("needle.quality.coverage", 0.82d);
        TraceStore.put("needle.quality.duplicateRatio", 0.10d);

        LearningSampleValidationMetadata meta = builder.build(
                "Why does a weak authority signal become useful after Self-Ask requery?",
                "The answer is supported by rechecked evidence.",
                "gemma4:26b",
                5,
                5,
                true,
                0.90d,
                "");

        assertTrue(meta.accepted());
        assertTrue(meta.needleRoi().needleSignalCandidate());
        assertTrue(meta.needleRoi().promoted());
        assertTrue(meta.needleRoi().signalValueScore() >= meta.thresholds().sampleScoreMin());
        assertEquals("accepted", meta.needleRoi().rejectReason());
        assertEquals(true, TraceStore.get("learning.roi.needleSignalCandidate"));
        assertEquals(true, TraceStore.get("learning.roi.promoted"));
        assertEquals(true, TraceStore.get("selfask.3way.requery.required"));
        assertEquals(true, TraceStore.get("selfask.3way.requery.confirmed"));
    }

    @Test
    void rejectsNeedleRoiPromotionWhenRequeryAttemptTimedOut() {
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
        TraceStore.put("selfask.3way.requery.confirmed", true);
        TraceStore.append("selfask.requery.attempts", Map.of("lane", "BQ", "failureClass", "timeout"));
        TraceStore.put("needle.triggered", true);
        TraceStore.put("needle.docs.count", 4);
        TraceStore.put("needle.urls.count", 3);
        TraceStore.put("needle.quality.authorityAvg", 0.95d);
        TraceStore.put("needle.quality.coverage", 0.85d);
        TraceStore.put("needle.quality.duplicateRatio", 0.05d);

        LearningSampleValidationMetadata meta = builder.build(
                "Why does timeout evidence need requery confirmation?",
                "The answer is supported by cited retrieval evidence.",
                "gemma4:26b",
                5,
                5,
                true,
                0.90d,
                "");

        assertFalse(meta.accepted());
        assertTrue(meta.needleRoi().needleSignalCandidate());
        assertFalse(meta.needleRoi().promoted());
        assertEquals("runtime_failure_timeout", meta.needleRoi().rejectReason());
        assertTrue(meta.rejectReasons().contains("needle_roi_runtime_failure"));
        assertEquals(false, TraceStore.get("learning.roi.promoted"));
        assertEquals("runtime_failure_timeout", TraceStore.getString("learning.roi.rejectReason"));
    }

    @Test
    void doesNotConfirmHighRiskRequeryFromLaneCoverageOnly() {
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));

        LearningSampleValidationMetadata meta = builder.build(
                "Legal investment contract advice for a lawsuit involving stale RAG search answers",
                "The answer cites several retrieval results.",
                "gemma4:26b",
                4,
                4,
                true,
                0.80d,
                "");

        assertEquals(1.0d, meta.selfAskLaneCoverage(), 0.0001d);
        assertTrue(meta.requery().required());
        assertFalse(meta.requery().confirmed());
        assertFalse(meta.accepted());
        assertTrue(meta.rejectReasons().contains("unconfirmed_high_risk_requery"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "unconfirmed_high_risk_requery".equals(row.get("label"))));
    }

    @Test
    void rejectsProviderDisabledSamples() {
        LearningSampleValidationMetadata meta = builder.build(
                "What is the current provider status?",
                "Provider is disabled.",
                "local",
                3,
                3,
                true,
                0.90d,
                "missing-key-or-unauthorized");

        assertFalse(meta.accepted());
        assertTrue(meta.rejectReasons().contains("provider_disabled"));
        assertEquals("rejected", TraceStore.getString("learning.validation.decision"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "provider_disabled".equals(row.get("label"))));
    }

    @Test
    void rejectsAfterFilterStarvationEvenWhenRawEvidenceAndLanesExist() {
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));
        TraceStore.put("selfask.3way.requery.confirmed", true);

        LearningSampleValidationMetadata meta = builder.build(
                "Why does filtered evidence disappear after retrieval?",
                "The answer claims filtered evidence was sufficient.",
                "gemma4:26b",
                5,
                0,
                true,
                0.90d,
                "");

        assertFalse(meta.accepted());
        assertTrue(meta.rejectReasons().contains("after_filter_starvation"));
        assertEquals(0, meta.runtime().afterFilterCount());
        assertEquals(0L, TraceStore.getLong("learning.validation.afterFilterCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "after_filter_starvation".equals(row.get("label"))));
        @SuppressWarnings("unchecked")
        List<String> contaminationSignals =
                (List<String>) TraceStore.get("learning.validation.contaminationSignals");
        assertTrue(contaminationSignals.contains("after_filter_starvation"));
    }

    @Test
    void rejectsHighContradictionSamplesWithoutRawEvidence() {
        TraceStore.put("overdrive.contradiction.mean", 0.82d);
        TraceStore.put("extremez.risk.primaryCause", "provider_disabled");
        TraceStore.put("selfask.3way.requery.confirmed", true);
        TraceStore.append("selfask.3way.events", Map.of("lane", "BQ"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "ER"));
        TraceStore.append("selfask.3way.events", Map.of("lane", "RC"));

        LearningSampleValidationMetadata meta = builder.build(
                "Why do the two official records disagree about the policy date?",
                "The answer cites several conflicting sources.",
                "gemma4:26b",
                5,
                5,
                true,
                0.85d,
                "");

        assertFalse(meta.accepted());
        assertEquals(0.82d, meta.contradictionScore(), 0.0001d);
        assertEquals("provider_disabled", meta.contradictionCause());
        assertTrue(meta.rejectReasons().contains("contradiction_risk"));
        assertEquals("QUARANTINE", meta.feedback().vectorDecision());
        assertEquals(0.82d, ((Number) TraceStore.get("learning.validation.contradictionScore")).doubleValue(), 0.0001d);
        assertEquals("provider_disabled", TraceStore.getString("learning.validation.contradictionCause"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "contradiction_risk".equals(row.get("label"))));
        @SuppressWarnings("unchecked")
        List<String> contaminationSignals =
                (List<String>) TraceStore.get("learning.validation.contaminationSignals");
        assertTrue(contaminationSignals.contains("evidence_conflict"));
    }

    @Test
    void ignoresPreviousValidationContradictionTraceAsInput() {
        TraceStore.put("learning.validation.contradictionScore", 0.99d);
        TraceStore.put("learning.validation.contradictionCause", "evidence_conflict");

        LearningSampleValidationMetadata meta = builder.build(
                "What is cache invalidation?",
                "The answer is supported by cited retrieval evidence.",
                "gemma4:26b",
                5,
                5,
                true,
                0.90d,
                "");

        assertTrue(meta.accepted());
        assertEquals(0.0d, meta.contradictionScore(), 0.0001d);
        assertEquals("unknown", meta.contradictionCause());
        assertFalse(meta.rejectReasons().contains("contradiction_risk"));
    }

    @Test
    void rejectsUnconfirmedHighRiskRequery() {
        LearningSampleValidationMetadata meta = builder.build(
                "Legal investment contract advice for a lawsuit",
                "Use this answer without citations.",
                "gemma4:26b",
                3,
                3,
                true,
                0.80d,
                "");

        assertEquals("factual", meta.questionType());
        assertTrue(meta.riskScore() >= 0.70d);
        assertTrue(meta.requery().required());
        assertFalse(meta.requery().confirmed());
        assertFalse(meta.accepted());
        assertTrue(meta.rejectReasons().contains("unconfirmed_high_risk_requery"));
    }

    @Test
    void tracesContaminationAndLowSampleScoreAsThresholdBreaks() {
        LearningSampleValidationMetadata meta = builder.build(
                "What did the raw search trace say?",
                "stacktrace Exception: at com.example.Secret api_key bearer token build failed",
                "local",
                0,
                0,
                false,
                0.10d,
                "");

        assertFalse(meta.accepted());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks =
                (List<Map<String, Object>>) TraceStore.get("learning.validation.thresholdBreaks");
        assertTrue(breaks.stream().anyMatch(row -> "contamination_risk".equals(row.get("label"))));
        assertTrue(breaks.stream().anyMatch(row -> "context_contamination_threshold".equals(row.get("label"))));
        assertTrue(breaks.stream().anyMatch(row -> "sample_score_below_threshold".equals(row.get("label"))));
    }

    @Test
    void requiresRequeryForHistoryContextContaminationSignal() {
        TraceStore.put("blackbox.risk.historyContaminationScore", 0.52d);
        TraceStore.put("prompt.memory.compressor.activated", true);

        LearningSampleValidationMetadata meta = builder.build(
                "What is the project runtime path?",
                "The answer is supported by cited retrieval evidence.",
                "gemma4:26b",
                5,
                5,
                true,
                0.80d,
                "");

        assertTrue(meta.requery().required());
        assertFalse(meta.requery().confirmed());
        assertFalse(meta.accepted());
        assertTrue(meta.rejectReasons().contains("unconfirmed_history_contamination_requery"));
        assertEquals("QUARANTINE", meta.feedback().vectorDecision());
        @SuppressWarnings("unchecked")
        List<String> contaminationSignals =
                (List<String>) TraceStore.get("learning.validation.contaminationSignals");
        assertTrue(contaminationSignals.contains("history_context_contamination"));
    }

    @Test
    void appliesDynamicThresholdsAndTracesRequeryPenalty() {
        TraceStore.put("learning.threshold.tuningDelta", 0.05d);

        LearningSampleValidationMetadata meta = builder.build(
                "Legal investment contract advice for a lawsuit",
                "Use this answer without citations.",
                "gemma4:26b",
                3,
                3,
                true,
                0.80d,
                "");

        assertEquals("dynamic", meta.thresholds().mode());
        assertTrue(meta.thresholds().sampleScoreMin() > 0.55d);
        assertTrue(meta.thresholds().contaminationMax() < 0.35d);
        assertEquals(0.55d, meta.thresholds().contradictionMax(), 0.0001d);
        assertTrue(meta.thresholds().requeryPenalty() >= 0.08d);
        assertEquals(meta.thresholds().sampleScoreMin(),
                ((Number) TraceStore.get("learning.threshold.sampleScoreMin")).doubleValue(), 0.0001d);
        assertEquals(meta.thresholds().contradictionMax(),
                ((Number) TraceStore.get("learning.threshold.contradictionMax")).doubleValue(), 0.0001d);
    }

    @Test
    void nonFiniteTraceDoubleUsesFallbackThresholds() {
        LearningSampleValidationMetadata baseline = builder.build(
                "Legal investment contract advice for a lawsuit",
                "Use this answer without citations.",
                "gemma4:26b",
                3,
                3,
                true,
                0.80d,
                "");

        TraceStore.clear();
        TraceStore.put("learning.threshold.tuningDelta", "NaN");
        LearningSampleValidationMetadata fromString = builder.build(
                "Legal investment contract advice for a lawsuit",
                "Use this answer without citations.",
                "gemma4:26b",
                3,
                3,
                true,
                0.80d,
                "");

        TraceStore.clear();
        TraceStore.put("learning.threshold.tuningDelta", Double.POSITIVE_INFINITY);
        LearningSampleValidationMetadata fromNumber = builder.build(
                "Legal investment contract advice for a lawsuit",
                "Use this answer without citations.",
                "gemma4:26b",
                3,
                3,
                true,
                0.80d,
                "");

        assertEquals(baseline.thresholds().sampleScoreMin(),
                fromString.thresholds().sampleScoreMin(), 0.0001d);
        assertEquals(baseline.thresholds().contaminationMax(),
                fromString.thresholds().contaminationMax(), 0.0001d);
        assertEquals(baseline.thresholds().contradictionMax(),
                fromString.thresholds().contradictionMax(), 0.0001d);
        assertEquals(baseline.thresholds().sampleScoreMin(),
                fromNumber.thresholds().sampleScoreMin(), 0.0001d);
        assertEquals(baseline.thresholds().contaminationMax(),
                fromNumber.thresholds().contaminationMax(), 0.0001d);
        assertEquals(baseline.thresholds().contradictionMax(),
                fromNumber.thresholds().contradictionMax(), 0.0001d);
        assertEquals(true, TraceStore.get("learning.validation.suppressed.readDouble"));
        assertEquals("invalid_number", TraceStore.get("learning.validation.suppressed.readDouble.errorType"));
        assertEquals("readDouble", TraceStore.get("learning.validation.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("learning.validation.suppressed.errorType"));
    }
}
