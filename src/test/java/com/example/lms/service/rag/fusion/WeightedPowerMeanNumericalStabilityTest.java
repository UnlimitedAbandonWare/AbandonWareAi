package com.example.lms.service.rag.fusion;

import com.example.lms.search.TraceStore;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeightedPowerMeanNumericalStabilityTest {
    private final WeightedPowerMeanFuser fuser = new WeightedPowerMeanFuser();

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest
    @ValueSource(doubles = {1.05, 1.2, 2.0, 3.0, 8.0})
    void tinyPositiveScoresKeepTheirRelativeMeanAcrossNovaPowerBand(double p) {
        double result = fuser.fuse(List.of(1e-200, 2e-200), p, null);
        double expectedRatio = Math.pow((1.0 + Math.pow(2.0, p)) / 2.0, 1.0 / p);
        assertTrue(Double.isFinite(result) && result > 0.0);
        assertEquals(expectedRatio, result / 1e-200, 1e-12);
    }

    @Test
    void novaUpperTailPathPreservesAdmissibleTinySourceScores() {
        NovaNextProperties props = new NovaNextProperties();
        props.setP0(2.0);
        props.setAlphaTwpm(0.0);
        var input = new NovaNextFusionService.ScoredResult(0.5);
        List<Double> sourceScores = List.of(1e-200, 2e-200);
        input.setSourceScores(sourceScores);
        input.setSourceCount(2);
        var output = new NovaNextFusionService(props).fuse(List.of(input));
        double result = ((Number) TraceStore.get("rag.fusion.twpm.upper.result")).doubleValue();
        assertEquals(Math.sqrt(3.0), result / 1e-200, 1e-12);
        assertEquals(2.0, TraceStore.get("hypernova.twpmP"));
        assertEquals(2.0, TraceStore.get("rag.fusion.twpm.upper.tailBoost"));
        assertEquals(props.getLambdaCvar(), TraceStore.get("hypernova.cvarPhi"));
        assertTrue(TraceStore.get("hypernova.clampApplied") instanceof Boolean);
        assertEquals(1, output.size());
        assertTrue(Double.isFinite(output.get(0).getAdjustedScore()));
        assertEquals(sourceScores, input.getSourceScores());
        assertEquals(2.0, props.getP0());
    }

    @Test
    void zeroWeightDoesNotLetAnExcludedLargeScoreEraseTheMean() {
        assertEquals(1.0, fuser.fuse(List.of(1e-200, 1e200), 2.0, List.of(1.0, 0.0)) / 1e-200, 1e-12);
    }

    @Test
    void ordinaryWeightsAndMissingWeightFallbackKeepTheirMean() {
        assertEquals(Math.sqrt(3.25), fuser.fuse(List.of(1.0, 2.0), 2.0, List.of(1.0, 3.0)), 1e-12);
        assertEquals(Math.sqrt(2.5), fuser.fuse(List.of(1.0, 2.0), 2.0, List.of(1.0)), 1e-12);
        assertEquals(Math.sqrt(2.5), fuser.fuse(List.of(1.0, 2.0), 2.0, Arrays.asList(null, 1.0)), 1e-12);
    }

    @Test
    void geometricAndNegativePowerContractsArePreserved() {
        assertEquals(0.5, fuser.fuse(List.of(0.25, 1.0), 0.0, null), 1e-12);
        assertEquals(4.0 / 3.0, fuser.fuse(List.of(1.0, 2.0), -1.0, null), 1e-12);
    }

    @Test
    void emptyZeroAndNullScoresRetainFallbackBehavior() {
        assertEquals(0.0, fuser.fuse(null, 2.0, null));
        assertEquals(0.0, fuser.fuse(List.of(), 2.0, null));
        assertEquals(0.0, fuser.fuse(List.of(0.0, 0.0), 2.0, null));
        assertEquals(0.0, fuser.fuse(List.of(1.0, 2.0), 2.0, List.of(0.0, 0.0)));
        assertEquals(Math.sqrt(0.5), fuser.fuse(Arrays.asList(null, 1.0), 2.0, null), 1e-12);
    }

    @Test
    void doesNotMutateScoresOrWeights() {
        var scores = new ArrayList<>(List.of(1e-200, 2e-200));
        var weights = new ArrayList<>(List.of(1.0, 2.0));
        fuser.fuse(scores, 2.0, weights);
        assertEquals(List.of(1e-200, 2e-200), scores);
        assertEquals(List.of(1.0, 2.0), weights);
    }
}
