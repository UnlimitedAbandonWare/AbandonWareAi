package com.example.lms.service.rag.fusion;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailWeightedPowerMeanFuserTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void lowerTailBoostPenalizesWeakEvidenceMoreThanPlainPowerMean() {
        WeightedPowerMeanFuser base = new WeightedPowerMeanFuser();
        TailWeightedPowerMeanFuser fuser = new TailWeightedPowerMeanFuser(base);
        List<Double> scores = List.of(0.20d, 0.90d, 0.95d);

        double plain = base.fuse(scores, 1.0d, null);
        double tailWeighted = fuser.fuseLowerTail(scores, 1.0d, null, 1.0d / 3.0d, 4.0d);

        assertTrue(tailWeighted < plain);
        assertEquals((0.20d * 4.0d + 0.90d + 0.95d) / 6.0d, tailWeighted, 1.0e-9);
    }

    @Test
    void clampsScoresAndTailControlsToStableRange() {
        TailWeightedPowerMeanFuser fuser = new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser());

        double out = fuser.fuseLowerTail(List.of(-1.0d, 0.5d, 2.0d), 1.0d, List.of(1.0d, 1.0d, 1.0d), -2.0d, 99.0d);

        assertTrue(out >= 0.0d && out <= 1.0d);
        assertEquals((0.0d * 99.0d + 0.5d + 1.0d) / 101.0d, out, 1.0e-9);
    }

    @Test
    void lowerTailFusionStoresCountOnlyTrace() {
        TailWeightedPowerMeanFuser fuser = new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser());

        double out = fuser.fuseLowerTail(List.of(0.20d, 0.90d, 0.95d), 1.0d, null, 1.0d / 3.0d, 4.0d);

        assertTrue(out > 0.0d);
        assertEquals(3, TraceStore.get("rag.fusion.twpm.inputCount"));
        assertEquals(1, TraceStore.get("rag.fusion.twpm.tailCount"));
        assertEquals(4.0d, TraceStore.get("rag.fusion.twpm.tailBoost"));
        assertEquals(out, TraceStore.get("rag.fusion.twpm.result"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("[0.2"));
    }

    @Test
    void upperTailBoostRaisesStrongEvidenceAndStoresCountOnlyTrace() {
        TailWeightedPowerMeanFuser fuser = new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser());
        List<Double> scores = List.of(0.20d, 0.90d, 0.95d);

        double lower = fuser.fuseLowerTail(scores, 1.0d, null, 1.0d / 3.0d, 4.0d);
        TraceStore.clear();
        double upper = fuser.fuseUpperTail(scores, 1.0d, null, 1.0d / 3.0d, 4.0d);

        assertTrue(upper > lower);
        assertEquals((0.20d + 0.90d + 0.95d * 4.0d) / 6.0d, upper, 1.0e-9);
        assertEquals(3, TraceStore.get("rag.fusion.twpm.upper.inputCount"));
        assertEquals(1, TraceStore.get("rag.fusion.twpm.upper.tailCount"));
        assertEquals(4.0d, TraceStore.get("rag.fusion.twpm.upper.tailBoost"));
        assertEquals(upper, TraceStore.get("rag.fusion.twpm.upper.result"));
        assertEquals("upper", TraceStore.get("hypernova.twpm.mode"));
        assertEquals(1.0d, TraceStore.get("hypernova.twpm.p"));
        assertEquals(1.0d, TraceStore.get("hypernova.twpmP"));
        assertEquals(3, TraceStore.get("hypernova.twpmInputCount"));
        assertEquals(upper, TraceStore.get("hypernova.twpmResult"));
        assertEquals(1.0d / 3.0d, (Double) TraceStore.get("hypernova.twpm.tailFraction"), 1.0e-9);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("[0.2"));
    }

    @Test
    void sourceDocumentsLowerAndUpperTailModes() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java"));

        assertTrue(source.contains("lower-tail"));
        assertTrue(source.contains("upper-tail"));
        assertTrue(source.contains("fuseUpperTail"));
    }
}
