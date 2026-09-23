package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaNextFusionBoundedProbeTest {

    @TestFactory
    Stream<DynamicTest> configuredPMaxUsesFiniteOwnedBandWithoutMutatingConfig() {
        return Stream.of(
                        new Candidate("below-band", 0.50d, 1.05d, true),
                        new Candidate("default", 3.0d, 3.0d, false),
                        new Candidate("exact-upper-edge", 8.0d, 8.0d, false),
                        new Candidate("above-upper-edge", 1_000_000.0d, 8.0d, true),
                        new Candidate("nan", Double.NaN, 3.0d, true),
                        new Candidate("positive-infinity", Double.POSITIVE_INFINITY, 3.0d, true),
                        new Candidate("negative-infinity", Double.NEGATIVE_INFINITY, 3.0d, true))
                .map(candidate -> DynamicTest.dynamicTest(candidate.name(), () -> assertBounded(candidate)));
    }

    private static void assertBounded(Candidate candidate) {
        TraceStore.clear();
        try {
            NovaNextProperties properties = new NovaNextProperties();
            properties.getGrandas().setPMax(candidate.configured());
            long configuredBits = Double.doubleToLongBits(properties.getGrandas().getPMax());
            NovaNextFusionService service = new NovaNextFusionService(properties);

            List<NovaNextFusionService.ScoredResult> output = service.fuse(List.of(
                    scored("high", 0.95d, List.of(0.95d, 0.70d)),
                    scored("low", 0.10d, List.of(0.10d, 0.05d))));

            double appliedMax = ((Number) TraceStore.get("hypernova.twpmP.max")).doubleValue();
            double appliedP = ((Number) TraceStore.get("hypernova.twpmP")).doubleValue();
            assertEquals(candidate.expectedAppliedMax(), appliedMax, 1.0e-9d);
            assertEquals(candidate.expectedBounded(), TraceStore.get("hypernova.twpmP.maxBounded"));
            assertTrue(Double.isFinite(appliedP));
            assertTrue(appliedP >= 1.05d - 1.0e-9d && appliedP <= appliedMax + 1.0e-9d);
            assertEquals(2, output.size(), "the bounded probe must exercise both ranked results");
            assertTrue(output.stream().allMatch(NovaNextFusionBoundedProbeTest::finiteInsideGuardBand));
            assertEquals(configuredBits,
                    Double.doubleToLongBits(properties.getGrandas().getPMax()),
                    "the probe must not rewrite configured pMax");
        } finally {
            TraceStore.clear();
        }
    }

    private static boolean finiteInsideGuardBand(NovaNextFusionService.ScoredResult result) {
        double adjusted = result.getAdjustedScore();
        double base = result.getBaseScore();
        double guard = result.getGuardBand();
        return Double.isFinite(adjusted)
                && adjusted >= Math.max(0.0d, base - guard) - 1.0e-9d
                && adjusted <= base + guard + 1.0e-9d;
    }

    private static NovaNextFusionService.ScoredResult scored(
            String id, double score, List<Double> sourceScores) {
        NovaNextFusionService.ScoredResult result = new NovaNextFusionService.ScoredResult(score);
        result.setId(id);
        result.setBaseScore(score);
        result.setSourceScores(sourceScores);
        result.setSourceCount(sourceScores.size());
        return result;
    }

    private record Candidate(
            String name, double configured, double expectedAppliedMax, boolean expectedBounded) {
    }
}
