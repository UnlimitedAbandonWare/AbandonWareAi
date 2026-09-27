package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.fusion.TailWeightedPowerMeanFuser;
import com.example.lms.service.rag.fusion.WeightedPowerMeanFuser;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import org.junit.jupiter.api.AfterEach;
import com.nova.protocol.alloc.SimpleRiskKAllocator;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaNextFusionServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void upperTailCandidateRisesOnlyInsideGuardBand() {
        NovaNextProperties props = new NovaNextProperties();
        NovaNextFusionService service = new NovaNextFusionService(props);
        NovaNextFusionService.ScoredResult tail = scored("tail", 1.0d, 0.95d);
        tail.setAuthorityAvg(0.90d);
        tail.setStrongCitationRate(1.0d);
        tail.setGrandasReadiness(0.90d);
        NovaNextFusionService.ScoredResult normal = scored("normal", 0.40d, 0.10d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(tail, normal));

        assertTrue(out.get(0).getAdjustedScore() >= out.get(0).getBaseScore());
        assertTrue(out.get(0).getAdjustedScore()
                <= out.get(0).getBaseScore() * (1.0d + props.getGrandas().getMaxAdjustment()) + 1.0e-9d);
        assertTrue(out.get(0).getTailSignal() >= 0.70d);
        assertEquals(props.getGrandas().getBodeC(), TraceStore.get("nova.next.bodeClamp.c"));
        assertTrue(TraceStore.get("nova.next.bodeClamp.result") instanceof Double);
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.clampApplied"));
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.active"));
        assertTrue(TraceStore.get("hypernova.twpmP") instanceof Double);
        assertEquals(props.getGrandas().getPMax(), (Double) TraceStore.get("hypernova.twpmP.max"), 1.0e-9d);
        assertTrue(TraceStore.get("hypernova.twpm.p") instanceof Double);
        assertEquals(0.25d, (Double) TraceStore.get("hypernova.twpm.tailFraction"), 1.0e-9d);
        assertEquals("upper", TraceStore.get("hypernova.twpm.mode"));
        assertTrue(TraceStore.get("hypernova.cvarFusedScore") instanceof Double);
        assertEquals(props.getLambdaCvar(), (Double) TraceStore.get("hypernova.cvarPhi"), 1.0e-9d);
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.dppApplied"));
        assertEquals("too_few_results",
                TraceStore.get("hypernova.dppDisabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.finalGatePassed"));
        assertEquals("downstream_gate_callback_pending",
                TraceStore.get("hypernova.finalGateDisabledReason"));
    }

    @Test
    void extremeConfiguredTwpmPowerIsBoundedBeforeFusion() {
        NovaNextProperties props = new NovaNextProperties();
        props.setP0(1_000_000.0d);
        props.setAlphaTwpm(1_000_000.0d);
        props.getGrandas().setPMax(1_000_000.0d);
        NovaNextFusionService service = new NovaNextFusionService(props);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(
                scored("sparse", 0.95d, 0.95d),
                scored("weak", 0.10d, 0.10d)));

        assertEquals(8.0d, (Double) TraceStore.get("hypernova.twpmP.max"), 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.twpmP.maxBounded"));
        assertTrue((Double) TraceStore.get("hypernova.twpmP") <= 8.0d);
        assertTrue(out.stream().allMatch(result -> Double.isFinite(result.getAdjustedScore())));
    }

    @Test
    void cvarFusedScoreTraceReportsActualTwpmCvarBlend() {
        NovaNextProperties props = new NovaNextProperties();
        props.setLambdaCvar(0.35d);
        NovaNextFusionService service = new NovaNextFusionService(props);
        NovaNextFusionService.ScoredResult candidate = scored("trace-contract", 0.80d, 0.80d);
        candidate.setSourceScores(List.of(0.20d, 0.80d));

        service.fuse(List.of(candidate));

        double twpm = ((Number) TraceStore.get("hypernova.twpmResult")).doubleValue();
        double cvar = ((Number) TraceStore.get("hypernova.cvarValue")).doubleValue();
        double expected = ((1.0d - props.getLambdaCvar()) * twpm)
                + (props.getLambdaCvar() * cvar);

        assertEquals(expected,
                ((Number) TraceStore.get("hypernova.cvarFusedScore")).doubleValue(),
                1.0e-6d);
    }

    @Test
    void dppRerankerAppliesWhenHypernovaHasEnoughCandidates() {
        NovaNextFusionService service = new NovaNextFusionService(
                new NovaNextProperties(),
                null,
                new DppDiversityReranker(),
                new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser()));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(
                scored("alpha duplicate", 0.92d, 0.80d),
                scored("alpha duplicate extra", 0.91d, 0.78d),
                scored("beta independent", 0.88d, 0.75d),
                scored("gamma independent", 0.86d, 0.70d)));

        assertEquals(4, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.dppApplied"));
        assertEquals(4, TraceStore.get("hypernova.dppInputCount"));
        assertEquals(4, TraceStore.get("hypernova.dppOutputCount"));
        assertEquals("", TraceStore.get("hypernova.dppDisabledReason"));
    }

    @Test
    void novaNextFusionDelegatesTwpmToCanonicalFuser() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/nova/protocol/fusion/NovaNextFusionService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("twpmFuser.fuseUpperTail("));
        assertFalse(source.contains("private static double weightedPowerMean("));
    }

    @Test
    void ordinaryCandidateAlmostHolds() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        NovaNextFusionService.ScoredResult normal = scored("normal", 0.50d, 0.10d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(normal));

        assertTrue(Math.abs(out.get(0).getAdjustedScore() - out.get(0).getBaseScore())
                <= out.get(0).getGuardBand());
    }

    @Test
    void riskKAllocatorDistributesBudgetAwayFromHighRiskCandidates() {
        NovaNextProperties props = new NovaNextProperties();
        props.setKTotal(12);
        NovaNextFusionService service = new NovaNextFusionService(props, new SimpleRiskKAllocator());
        NovaNextFusionService.ScoredResult highRisk = scored("high-risk", 0.92d, 0.80d);
        highRisk.setContradictionRate(0.95d);
        NovaNextFusionService.ScoredResult lowRisk = scored("low-risk", 0.88d, 0.70d);
        lowRisk.setContradictionRate(0.05d);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(highRisk, lowRisk));

        assertEquals(12, out.get(0).getRiskKAllocation() + out.get(1).getRiskKAllocation());
        assertTrue(out.get(1).getRiskKAllocation() > out.get(0).getRiskKAllocation());
        assertEquals(Boolean.TRUE, TraceStore.get("nova.hypernova.riskK.used"));
        assertEquals(12, TraceStore.get("nova.hypernova.riskK.alloc.sum"));
        Map<?, ?> riskKAlloc = (Map<?, ?>) TraceStore.get("hypernova.riskKAlloc");
        assertEquals(12, riskKAlloc.get("sum"));
        assertEquals(12, riskKAlloc.get("totalK"));
    }

    @Test
    void outOfRangeSourceScoresAreIgnoredWithScaleMismatchTrace() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        NovaNextFusionService.ScoredResult calibrated = scored("calibrated", 0.90d, 0.30d);
        calibrated.setSourceScores(List.of(0.80d, 0.85d));
        NovaNextFusionService.ScoredResult mixedScale = scored("mixed-scale", 0.89d, 0.30d);
        mixedScale.setSourceScores(List.of(42.0d, 87.0d));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(calibrated, mixedScale));

        assertEquals(2, TraceStore.get("hypernova.sourceScoreScaleMismatchCount"));
        assertEquals("fallback_to_base_norm", TraceStore.get("hypernova.sourceScoreScaleMismatchPolicy"));
        assertTrue(out.get(1).getAdjustedScore() <= out.get(0).getAdjustedScore());
    }

    @Test
    void nullEmptyDisabledAndNanInputArePassThrough() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());
        assertTrue(service.fuse(null).isEmpty());
        List<NovaNextFusionService.ScoredResult> empty = List.of();
        assertSame(empty, service.fuse(empty));

        NovaNextProperties disabled = new NovaNextProperties();
        disabled.setEnabled(false);
        NovaNextFusionService disabledService = new NovaNextFusionService(disabled);
        List<NovaNextFusionService.ScoredResult> input = List.of(new NovaNextFusionService.ScoredResult(Double.NaN));
        assertSame(input, disabledService.fuse(input));

        List<NovaNextFusionService.ScoredResult> nan = service.fuse(input);
        assertEquals(0.0d, nan.get(0).getAdjustedScore());
    }

    @Test
    void fusionFailSoftRecordsRedactedReason() {
        NovaNextProperties props = new NovaNextProperties();
        props.setKTotal(4);
        NovaNextFusionService service = new NovaNextFusionService(props,
                (logits, risk, totalK, temp, floor) -> {
                    throw new IllegalStateException("raw scorer detail should not leak");
                });
        List<NovaNextFusionService.ScoredResult> input = List.of(
                scored("tail", 0.90d, 0.90d),
                scored("normal", 0.60d, 0.20d));

        List<NovaNextFusionService.ScoredResult> out = service.fuse(input);

        assertSame(input, out);
        assertEquals("fuse", TraceStore.get("nova.next.failSoft.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("nova.next.failSoft"));
        assertEquals("IllegalStateException", TraceStore.get("nova.next.failSoft.errorType"));
        assertTrue(String.valueOf(TraceStore.get("nova.next.failSoft.errorMessageHash")).startsWith("hash:"));
        assertEquals("raw scorer detail should not leak".length(),
                TraceStore.get("nova.next.failSoft.errorMessageLength"));
        assertNull(TraceStore.get("nova.next.failSoft.message"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw scorer detail should not leak"));
    }

    @Test
    void clampAppliedRemainsFalseWhenBodeClampDoesNotChangeDelta() {
        NovaNextProperties props = new NovaNextProperties();
        props.getGrandas().setBodeC(0.0d);
        NovaNextFusionService service = new NovaNextFusionService(props);
        NovaNextFusionService.ScoredResult candidate = scored("unclamped", 1.0d, 0.95d);
        candidate.setAuthorityAvg(0.90d);
        candidate.setStrongCitationRate(1.0d);
        candidate.setGrandasReadiness(0.90d);

        service.fuse(List.of(candidate));

        double input = ((Number) TraceStore.get("nova.next.bodeClamp.input")).doubleValue();
        double result = ((Number) TraceStore.get("nova.next.bodeClamp.result")).doubleValue();
        assertTrue(input > 1.0e-9d);
        assertEquals(input, result, 1.0e-12d);
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.clampApplied"));
    }

    @Test
    void allNegativeRawScoresRemainOrderedWithFiniteNonDegenerateGuardBands() {
        NovaNextFusionService service = new NovaNextFusionService(new NovaNextProperties());

        List<NovaNextFusionService.ScoredResult> forward = service.fuse(negativeFixture(false));
        List<NovaNextFusionService.ScoredResult> reverse = service.fuse(negativeFixture(true));
        List<NovaNextFusionService.ScoredResult> equal = service.fuse(List.of(
                scored("equal-b", -0.40d, 0.10d),
                scored("equal-a", -0.40d, 0.10d)));
        List<String> expectedOrder = List.of("least-negative", "middle", "most-negative");

        assertEquals(expectedOrder,
                forward.stream().map(NovaNextFusionService.ScoredResult::getId).toList());
        assertEquals(expectedOrder,
                reverse.stream().map(NovaNextFusionService.ScoredResult::getId).toList());
        assertEquals(List.of("equal-a", "equal-b"),
                equal.stream().map(NovaNextFusionService.ScoredResult::getId).toList());
        List<NovaNextFusionService.ScoredResult> checked = new java.util.ArrayList<>(forward);
        checked.addAll(equal);
        for (NovaNextFusionService.ScoredResult result : checked) {
            assertTrue(Double.isFinite(result.getAdjustedScore()));
            assertTrue(Double.isFinite(result.getGuardBand()));
            assertTrue(result.getGuardBand() > 0.0d, "raw finite scores need a non-degenerate interval");
            assertTrue(result.getAdjustedScore()
                    >= result.getBaseScore() - result.getGuardBand() - 1.0e-9d);
            assertTrue(result.getAdjustedScore()
                    <= result.getBaseScore() + result.getGuardBand() + 1.0e-9d);
        }
    }

    @Test
    void calibratedSourceScoreIsNotRenormalizedByUnrelatedRawMaximum() {
        List<List<Double>> cvarInputs = new java.util.ArrayList<>();
        CvarAggregator capturingCvar = new CvarAggregator() {
            @Override
            public double cvarAtQuantile(List<Double> scores, double quantile) {
                cvarInputs.add(List.copyOf(scores));
                return super.cvarAtQuantile(scores, quantile);
            }
        };
        NovaNextFusionService service = new NovaNextFusionService(
                new NovaNextProperties(), null, null, null, capturingCvar);
        NovaNextFusionService.ScoredResult raw = scored("raw", 100.0d, 0.20d);
        raw.setSourceScores(List.of(100.0d));
        NovaNextFusionService.ScoredResult calibrated = scored("calibrated", 0.80d, 0.20d);
        calibrated.setSourceScores(List.of(0.80d));

        service.fuse(List.of(raw, calibrated));

        assertEquals(List.of(0.80d), cvarInputs.get(1));
    }

    @Test
    void dppRerankPreservesOriginalObjectIdentityAndIdMetadataBinding() {
        NovaNextFusionService service = new NovaNextFusionService(
                new NovaNextProperties(),
                null,
                new DppDiversityReranker(new DppDiversityReranker.Config(0.50d, 4)),
                null);
        NovaNextFusionService.ScoredResult a = scored("id-a", 0.10d, 0.10d);
        a.setSource("source-a");
        NovaNextFusionService.ScoredResult b = scored("id-b", 0.95d, 0.95d);
        b.setSource("source-b");
        NovaNextFusionService.ScoredResult c = scored("id-c", 0.70d, 0.70d);
        c.setSource("source-c");
        Map<String, NovaNextFusionService.ScoredResult> original = Map.of(
                "id-a", a,
                "id-b", b,
                "id-c", c);

        List<NovaNextFusionService.ScoredResult> out = service.fuse(List.of(a, b, c));

        assertEquals(3, out.size());
        for (NovaNextFusionService.ScoredResult result : out) {
            assertSame(original.get(result.getId()), result);
            assertEquals("source-" + result.getId().substring(3), result.getSource());
        }
    }

    private static List<NovaNextFusionService.ScoredResult> negativeFixture(boolean reverse) {
        NovaNextFusionService.ScoredResult mostNegative = scored("most-negative", -0.90d, 0.10d);
        NovaNextFusionService.ScoredResult middle = scored("middle", -0.40d, 0.10d);
        NovaNextFusionService.ScoredResult leastNegative = scored("least-negative", -0.10d, 0.10d);
        return reverse
                ? List.of(leastNegative, middle, mostNegative)
                : List.of(mostNegative, leastNegative, middle);
    }

    private static NovaNextFusionService.ScoredResult scored(String id, double score, double tail) {
        NovaNextFusionService.ScoredResult sr = new NovaNextFusionService.ScoredResult(score);
        sr.setId(id);
        sr.setBaseScore(score);
        sr.setSourceScores(List.of(score));
        sr.setSourceCount(1);
        sr.setTailSignal(tail);
        return sr;
    }
}
