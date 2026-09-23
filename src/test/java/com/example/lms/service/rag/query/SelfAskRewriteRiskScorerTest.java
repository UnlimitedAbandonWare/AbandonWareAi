package com.example.lms.service.rag.query;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskRewriteRiskScorerTest {

    @Test
    void rewriteRiskNumericParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/query/SelfAskRewriteRiskScorer.java"));

        assertParserCatchNarrowed(source, "private static double toDouble(Object value)");
    }

    @Test
    void malformedNumericRewriteRiskTraceRecordsClassifiedSuppressionWithoutRawLeak() {
        String raw = "ownerToken=raw-secret";
        TraceStore.clear();
        try {
            SelfAskRewriteRiskScorer.score(
                    "rewrite risk parser redaction",
                    Map.of("resource.valueScore", raw),
                    Map.of(),
                    5,
                    5,
                    0.20d,
                    0.12d,
                    0.55d,
                    true);

            assertEquals(Boolean.TRUE, TraceStore.get("selfask.rewriteRisk.suppressed.toDouble"));
            assertEquals("invalid_number",
                    TraceStore.get("selfask.rewriteRisk.suppressed.toDouble.errorType"));
            assertEquals("toDouble", TraceStore.get("selfask.rewriteRisk.suppressed.stage"));
            assertEquals("invalid_number", TraceStore.get("selfask.rewriteRisk.suppressed.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(raw), trace);
            assertFalse(trace.contains("NumberFormatException"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void honestyAssessmentFlagsSensitiveInferenceWhenCorrectionExists() {
        SelfAskRewriteRiskScorer.RewriteHonesty honesty = SelfAskRewriteRiskScorer.assessRewriteHonesty(
                "션은 준우가 자살하라고 했다고 해석했지만, 준우는 그런 뜻이 절대 아니고 손절선을 잡으라는 말이었다고 정정했다.",
                "준우 자살 권유 인신공격 확정");

        assertEquals("OVERREACH", honesty.honestyStatus());
        assertEquals("sensitive_inference", honesty.overreachType());
        assertTrue(honesty.overreachScore() >= 0.70d);
        assertFalse(honesty.sourceHash12().isBlank());
    }

    @Test
    void rewriteOverreachCanBecomePrimaryRiskFactor() {
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "speaker-corrected conversation rewrite",
                Map.of(
                        "resource.valueScore", 0.10d,
                        "resource.optimismScore", 0.10d,
                        "resource.riskAdjustedConfidence", 0.85d,
                        "selfask.rewrite.honestyStatus", "OVERREACH",
                        "selfask.rewrite.overreachType", "quote_to_fact",
                        "selfask.rewrite.overreachScore", 0.88d,
                        "selfask.rewrite.sourceHash", "hash:abc123"),
                Map.of(),
                5,
                5,
                0.20d,
                0.12d,
                0.55d,
                true);

        assertEquals("rewriteOverreach", score.primaryFactor());
        assertEquals("OVERREACH", score.rewriteHonestyStatus());
        assertEquals("quote_to_fact", score.rewriteOverreachType());
        assertEquals(0.88d, score.rewriteOverreachScore());
        assertTrue(score.components().get("rewriteOverreach") > 0.0d);
    }

    @Test
    void lowRiskRequestStaysLowAndClamped() {
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "simple used item listing copy",
                Map.of(
                        "resource.valueScore", 0.20d,
                        "resource.optimismScore", 0.20d,
                        "resource.riskAdjustedConfidence", 0.80d),
                Map.of(),
                5,
                5,
                0.47d,
                0.12d,
                0.55d,
                true);

        assertEquals("LOW", score.rewriteRiskBand());
        assertTrue(score.rewriteRiskScore() >= 0.0d && score.rewriteRiskScore() < 0.35d);
        assertTrue(score.rewriteTemperatureWeighted() >= 0.12d);
        assertTrue(score.rewriteTemperatureWeighted() <= 0.55d);
        assertTrue(score.components().containsKey("value"));
        assertEquals(score.rewriteRiskScore(), score.accumulatedRiskScore());
        score.laneWeights().values().forEach(v -> assertTrue(v >= 0.0d && v <= 3.0d));
    }

    @Test
    void starvationAndProviderFailureRaiseHighRisk() {
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "latest DGX Spark price risk comparison",
                Map.of(
                        "resource.valueScore", 0.90d,
                        "resource.optimismScore", 0.80d,
                        "resource.riskAdjustedConfidence", 0.20d,
                        "wantsFresh", true),
                Map.of(
                        "web.naver.providerDisabled", true,
                        "web.brave.providerDisabled", true,
                        "web.naver.filter.rawCount", 4,
                        "web.naver.afterFilterCount", 0,
                        "overdrive.contradiction.mean", 0.90d,
                        "web.await.events.count", 4,
                        "web.await.events.timeout.count", 3),
                0,
                8,
                0.19d,
                0.12d,
                0.55d,
                true);

        assertEquals("HIGH", score.rewriteRiskBand());
        assertTrue(score.rewriteRiskScore() >= 0.70d);
        assertTrue(score.accumulatedRiskScore() >= score.currentRiskScore());
        assertTrue(score.evidenceRisk() > score.preRisk());
        assertTrue(score.laneWeights().get("RC") > 1.0d);
        assertTrue(score.components().get("providerFailure") > 0.0d);
    }

    @Test
    void disabledScorerKeepsUniformWeightsAndBaseTemperature() {
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "latest risky query",
                Map.of("resource.valueScore", 1.0d),
                Map.of("web.naver.providerDisabled", true),
                0,
                8,
                0.33d,
                0.12d,
                0.55d,
                false);

        assertEquals(false, score.enabled());
        assertEquals(0.33d, score.rewriteTemperatureWeighted());
        assertEquals(Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d), score.laneWeights());
    }

    @Test
    void rejectedStarvationEmergenceRaisesRiskAndBoostsRcWithinClamp() {
        Map<String, Object> metadata = Map.of(
                "resource.valueScore", 0.90d,
                "resource.optimismScore", 0.80d,
                "resource.riskAdjustedConfidence", 0.20d,
                "wantsFresh", true);
        Map<String, Object> trace = Map.of(
                "web.naver.providerDisabled", true,
                "web.brave.providerDisabled", true,
                "web.naver.filter.rawCount", 4,
                "web.naver.afterFilterCount", 0,
                "overdrive.contradiction.mean", 0.90d,
                "learning.validation.decision", "rejected",
                "learning.validation.rejectReasons", List.of("final_gate_failed", "provider_disabled"),
                "cfvm.kalloc.reward", -0.50d);
        SelfAskRewriteRiskScorer.Score baseline = SelfAskRewriteRiskScorer.score(
                "latest DGX Spark price risk comparison",
                metadata,
                trace,
                0,
                8,
                0.19d,
                0.12d,
                0.55d,
                true);

        SelfAskRewriteRiskScorer.Score emergent = SelfAskRewriteRiskScorer.score(
                "latest DGX Spark price risk comparison",
                metadata,
                trace,
                0,
                8,
                0.19d,
                0.12d,
                0.55d,
                true,
                SelfAskRewriteRiskScorer.EmergentConfig.defaults());

        assertEquals(SelfAskRewriteRiskScorer.EMERGENT_POLICY, emergent.policy());
        assertTrue(emergent.emergentAdjustment().riskDelta() > 0.0d);
        assertTrue(emergent.emergentAdjustment().riskDelta() <= 0.08d);
        assertTrue(emergent.rewriteRiskScore() >= baseline.rewriteRiskScore());
        assertTrue(emergent.emergentAdjustment().searchRangeDelta() > 0.0d);
        assertTrue(emergent.emergentAdjustment().searchRangeDelta() <= 0.08d);
        assertTrue(emergent.laneWeights().get("RC") > baseline.laneWeights().get("RC"));
    }

    @Test
    void highRiskEmergenceCoolsRewriteTemperatureAndKeepsRangeConservative() {
        Map<String, Object> metadata = Map.of(
                "resource.valueScore", 0.90d,
                "resource.optimismScore", 0.80d,
                "resource.riskAdjustedConfidence", 0.20d,
                "wantsFresh", true);
        Map<String, Object> trace = Map.of(
                "web.naver.providerDisabled", true,
                "web.brave.providerDisabled", true,
                "web.naver.filter.rawCount", 4,
                "web.naver.afterFilterCount", 0,
                "overdrive.contradiction.mean", 0.90d,
                "web.await.events.count", 4,
                "web.await.events.timeout.count", 3,
                "learning.validation.decision", "rejected",
                "learning.validation.rejectReasons", List.of("final_gate_failed", "provider_disabled"));

        SelfAskRewriteRiskScorer.Score emergent = SelfAskRewriteRiskScorer.score(
                "latest DGX Spark price risk comparison",
                metadata,
                trace,
                0,
                8,
                0.30d,
                0.12d,
                0.35d,
                true,
                SelfAskRewriteRiskScorer.EmergentConfig.defaults());

        assertTrue(emergent.rewriteTemperatureWeighted() <= 0.30d);
        assertTrue(emergent.emergentAdjustment().temperatureDelta() <= 0.0d);
        assertTrue(emergent.emergentAdjustment().searchRangeDelta() <= 0.08d);
        assertTrue(emergent.components().get("providerFailure") > 0.0d);
        assertTrue(emergent.components().get("afterFilterStarvation") > 0.0d);
        assertTrue(emergent.components().get("contradiction") > 0.0d);
        assertTrue(emergent.components().get("latencyPressure") > 0.0d);
    }

    @Test
    void acceptedHighEvidenceEmergenceSoftensRiskWithoutRangeExpansion() {
        Map<String, Object> metadata = Map.of(
                "resource.valueScore", 0.70d,
                "resource.optimismScore", 0.35d,
                "resource.riskAdjustedConfidence", 0.75d);
        Map<String, Object> trace = Map.of(
                "learning.validation.decision", "accepted",
                "learning.validation.sampleScore", 0.86d,
                "learning.validation.contextDiversity", 0.45d,
                "learning.validation.selfAskLaneCoverage", 1.0d,
                "learning.validation.requeryRequired", true,
                "learning.validation.requeryConfirmed", true,
                "cfvm.reward.adjusted", 0.60d);
        SelfAskRewriteRiskScorer.Score baseline = SelfAskRewriteRiskScorer.score(
                "evidence backed comparison",
                metadata,
                trace,
                5,
                5,
                0.25d,
                0.12d,
                0.55d,
                true);

        SelfAskRewriteRiskScorer.Score emergent = SelfAskRewriteRiskScorer.score(
                "evidence backed comparison",
                metadata,
                trace,
                5,
                5,
                0.25d,
                0.12d,
                0.55d,
                true,
                SelfAskRewriteRiskScorer.EmergentConfig.defaults());

        assertTrue(emergent.emergentAdjustment().riskDelta() < 0.0d);
        assertTrue(emergent.emergentAdjustment().riskDelta() >= -0.08d);
        assertTrue(emergent.rewriteRiskScore() <= baseline.rewriteRiskScore());
        assertEquals(0.0d, emergent.emergentAdjustment().searchRangeDelta());
        assertEquals("validated_soften", emergent.emergentAdjustment().reason());
    }

    @Test
    void acceptedEvidenceSeparatesCoolValidationAndWarmerExplorationTemperature() {
        Map<String, Object> trace = Map.of(
                "learning.validation.decision", "accepted",
                "learning.validation.sampleScore", 0.92d,
                "learning.validation.contextDiversity", 0.70d,
                "learning.validation.selfAskLaneCoverage", 1.0d,
                "learning.validation.requeryRequired", true,
                "learning.validation.requeryConfirmed", true,
                "cfvm.reward.adjusted", 0.70d);

        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "evidence backed creative answer synthesis",
                Map.of(
                        "resource.valueScore", 0.55d,
                        "resource.optimismScore", 0.30d,
                        "resource.riskAdjustedConfidence", 0.82d),
                trace,
                7,
                7,
                0.24d,
                0.12d,
                0.55d,
                true,
                SelfAskRewriteRiskScorer.EmergentConfig.defaults());

        assertEquals("validated_soften", score.emergentAdjustment().reason());
        assertTrue(score.validationTemperature() <= score.rewriteTemperatureWeighted());
        assertTrue(score.explorationTemperature() >= score.rewriteTemperatureWeighted());
        assertTrue(score.explorationTemperature() > score.validationTemperature());
        assertTrue(score.components().containsKey("validationTemperature"));
        assertTrue(score.components().containsKey("explorationTemperature"));
    }

    @Test
    void disabledEmergentConfigPreservesBaselineScoreTemperatureAndWeights() {
        Map<String, Object> trace = Map.of(
                "web.naver.providerDisabled", true,
                "web.naver.filter.rawCount", 4,
                "web.naver.afterFilterCount", 0,
                "learning.validation.decision", "rejected");
        SelfAskRewriteRiskScorer.Score baseline = SelfAskRewriteRiskScorer.score(
                "latest risky query",
                Map.of("resource.valueScore", 0.90d),
                trace,
                0,
                8,
                0.22d,
                0.12d,
                0.55d,
                true);
        SelfAskRewriteRiskScorer.Score disabled = SelfAskRewriteRiskScorer.score(
                "latest risky query",
                Map.of("resource.valueScore", 0.90d),
                trace,
                0,
                8,
                0.22d,
                0.12d,
                0.55d,
                true,
                SelfAskRewriteRiskScorer.EmergentConfig.disabled());

        assertEquals(baseline.rewriteRiskScore(), disabled.rewriteRiskScore());
        assertEquals(baseline.rewriteTemperatureWeighted(), disabled.rewriteTemperatureWeighted());
        assertEquals(baseline.laneWeights(), disabled.laneWeights());
        assertEquals(SelfAskRewriteRiskScorer.POLICY, disabled.policy());
        assertEquals(false, disabled.emergentAdjustment().enabled());
    }

    @Test
    void searchRangeDeltaOnlyExpandsForRecoveryRiskAndIsClamped() {
        SelfAskRewriteRiskScorer.EmergentConfig config =
                new SelfAskRewriteRiskScorer.EmergentConfig(true, 0.08d, 0.04d, 0.20d, 0.05d);
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "latest DGX Spark price risk comparison",
                Map.of(
                        "resource.valueScore", 0.90d,
                        "resource.optimismScore", 0.80d,
                        "resource.riskAdjustedConfidence", 0.20d,
                        "wantsFresh", true),
                Map.of(
                        "web.naver.providerDisabled", true,
                        "web.naver.filter.rawCount", 4,
                        "web.naver.afterFilterCount", 0,
                        "overdrive.contradiction.mean", 0.95d,
                        "learning.validation.decision", "rejected"),
                0,
                8,
                0.19d,
                0.12d,
                0.55d,
                true,
                config);

        assertTrue(score.emergentAdjustment().searchRangeDelta() > 0.0d);
        assertTrue(score.emergentAdjustment().searchRangeDelta() <= 0.05d);
    }

    @Test
    void accumulatedRiskUsesPreviousScoreAndSpikePenalty() {
        SelfAskRewriteRiskScorer.Score score = SelfAskRewriteRiskScorer.score(
                "latest high risk query",
                Map.of(
                        "resource.valueScore", 0.90d,
                        "resource.optimismScore", 0.80d,
                        "resource.riskAdjustedConfidence", 0.10d,
                        "wantsFresh", true),
                Map.of(
                        "ml.risk.rewrite.accumulatedScore", 0.10d,
                        "web.naver.filter.rawCount", 4,
                        "web.naver.afterFilterCount", 0,
                        "overdrive.contradiction.mean", 0.80d),
                0,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);

        double baseline = 0.70d * 0.10d + 0.30d * score.currentRiskScore();
        assertTrue(score.spikePenalty() > 0.0d);
        assertTrue(score.accumulatedRiskScore() > baseline);
        assertTrue(score.components().containsKey("spikePenalty"));
    }

    @Test
    void primaryFactorSeparatesProviderStarvationContradictionAndLatency() {
        SelfAskRewriteRiskScorer.Score provider = SelfAskRewriteRiskScorer.score(
                "provider failure query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.naver.providerDisabled", true, "web.brave.providerDisabled", true,
                        "web.serpapi.providerDisabled", true),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);
        assertEquals("providerFailure", provider.primaryFactor());

        SelfAskRewriteRiskScorer.Score starvation = SelfAskRewriteRiskScorer.score(
                "starved query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.naver.filter.rawCount", 4, "web.naver.afterFilterCount", 0),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);
        assertEquals("afterFilterStarvation", starvation.primaryFactor());

        SelfAskRewriteRiskScorer.Score contradiction = SelfAskRewriteRiskScorer.score(
                "contradiction query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("overdrive.contradiction.mean", 0.95d),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);
        assertEquals("contradiction", contradiction.primaryFactor());

        SelfAskRewriteRiskScorer.Score latency = SelfAskRewriteRiskScorer.score(
                "latency query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.await.events.count", 4, "web.await.skipped.count", 4),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);
        assertEquals("latencyPressure", latency.primaryFactor());
    }

    @Test
    void tavilyProviderFailureAndAfterFilterStarvationContributeToRisk() {
        SelfAskRewriteRiskScorer.Score provider = SelfAskRewriteRiskScorer.score(
                "tavily provider failure query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.tavily.providerDisabled", true),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);

        assertTrue(provider.components().get("providerFailure") > 0.0d);

        SelfAskRewriteRiskScorer.Score starvation = SelfAskRewriteRiskScorer.score(
                "tavily starved query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.tavily.returnedCount", 4, "web.tavily.afterFilterCount", 0),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);

        assertEquals(0.20d, starvation.components().get("afterFilterStarvation"));
        assertEquals("afterFilterStarvation", starvation.primaryFactor());

        SelfAskRewriteRiskScorer.Score latency = SelfAskRewriteRiskScorer.score(
                "tavily cooldown query",
                Map.of("resource.riskAdjustedConfidence", 0.95d),
                Map.of("web.failsoft.rateLimitBackoff.tavily.remainingMs", 1500L),
                3,
                8,
                0.20d,
                0.12d,
                0.55d,
                true);

        assertEquals(0.07d, latency.components().get("latencyPressure"));
        assertEquals("latencyPressure", latency.primaryFactor());
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
