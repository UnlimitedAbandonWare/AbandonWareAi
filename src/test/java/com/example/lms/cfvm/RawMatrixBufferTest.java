package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmKallocLearningProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawMatrixBufferTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void keepsNewestNineCompactSlotsInInsertionOrder() throws Exception {
        RawMatrixBuffer buffer = new RawMatrixBuffer();

        for (int i = 1; i <= 12; i++) {
            buffer.add(i, i * 10L, i * 100L);
        }

        List<RawMatrixBuffer.Entry> snapshot = buffer.snapshot();
        assertEquals(9, buffer.size());
        assertEquals(9, snapshot.size());
        assertEquals(4L, snapshot.get(0).patternId());
        assertEquals(12L, snapshot.get(8).patternId());
    }

    @Test
    void snapshotIsImmutableCopy() throws Exception {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.add(7L, 1L, 2L);

        List<RawMatrixBuffer.Entry> snapshot = buffer.snapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new RawMatrixBuffer.Entry(9L, 9L, 9L)));
        assertEquals(1, buffer.size());
    }

    @Test
    void updateWeightRebalancesBoltzmannWeightsAndTraceBreadcrumb() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(3);

        buffer.updateWeight(1, 0.75d);
        buffer.updateWeight(9, 0.50d);

        double[] weights = buffer.getWeights();
        assertEquals(3, weights.length);
        assertEquals(1.0d, weights[0] + weights[1] + weights[2], 1.0e-9d);
        assertTrue(weights[1] > weights[0]);
        assertTrue(weights[1] > weights[2]);
        weights[1] = 0.0d;
        assertEquals(1.0d, buffer.getWeights()[0] + buffer.getWeights()[1] + buffer.getWeights()[2], 1.0e-9d);
        assertEquals("BOLTZMANN_LEARNING_ACTIVE", TraceStore.get("cfvm.rawBuffer.weightMode"));
        assertEquals(1, TraceStore.get("cfvm.rawBuffer.weightUpdated.slot"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawBuffer.boltzmannRebalanced"));
        assertEquals(0.35d, (Double) TraceStore.get("cfvm.boltzmannTemp"), 0.0001d);
        assertEquals("CfvmKallocLearningProperties", TraceStore.get("cfvm.tempSource"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.tempAnnealApplied"));
    }

    @Test
    void constructorUsesCfvmKallocTemperatureAsSingleSourceOfTruth() {
        CfvmKallocLearningProperties props = new CfvmKallocLearningProperties();
        props.setBoltzmannTemperature(0.20d);
        RawMatrixBuffer buffer = new RawMatrixBuffer(props);

        buffer.updateWeight(1, 0.75d);

        assertEquals(0.20d, (Double) TraceStore.get("cfvm.boltzmannTemp"), 0.0001d);
        assertEquals("CfvmKallocLearningProperties", TraceStore.get("cfvm.tempSource"));
    }

    @Test
    void settingBoltzmannTempPublishesHarmonyAliasTrace() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(3);

        buffer.setBoltzmannTemp(0.5d);

        assertEquals(0.5d, (Double) TraceStore.get("cfvm.boltzmannTemp"), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.tempAnnealApplied"));
    }

    @Test
    void settingBoltzmannTempImmediatelyRebalancesEffectiveWeights() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(3);
        buffer.updateWeight(1, 1.0d);
        double[] before = buffer.getWeights();

        buffer.setBoltzmannTemp(0.10d);

        double[] after = buffer.getWeights();
        assertFalse(Arrays.equals(before, after));
        assertTrue(after[1] > before[1]);
        assertEquals(1.0d, after[0] + after[1] + after[2], 1.0e-12d);
    }

    @Test
    void restoreRejectsNegativeSnapshotWithoutMutatingState() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(3, 0.35d);
        buffer.updateWeight(1, 0.25d);
        double[] before = buffer.getWeights();

        assertThrows(IllegalArgumentException.class, () ->
                buffer.restoreFromSnapshot(new double[]{0.20d, -0.10d, 0.90d}, 0.35d));

        assertEquals("invalid_weight", TraceStore.get("cfvm.rawBuffer.restoreSkipped"));
        assertArrayEquals(before, buffer.getWeights(), 1.0e-12d);
        assertEquals(0.35d, buffer.getBoltzmannTemp(), 1.0e-12d);
    }

    @Test
    void javadocStatesBoltzmannLearningIsLocalSoftmaxOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/cfvm/RawMatrixBuffer.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("CFVM_BOLTZMANN_LEARNING"));
        assertTrue(source.contains("BOLTZMANN_LEARNING_ACTIVE"));
        assertTrue(source.contains("local Boltzmann softmax"));
    }
}
