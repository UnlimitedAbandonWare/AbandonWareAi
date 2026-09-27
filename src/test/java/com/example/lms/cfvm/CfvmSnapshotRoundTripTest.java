package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmSnapshotRoundTripTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void boltzmannWeightsSurviveSnapshotRestore() {
        RawMatrixBuffer source = new RawMatrixBuffer(9, 0.35d);
        source.updateWeight(3, 0.90d);
        source.setBoltzmannTemp(0.42d);

        RawMatrixBuffer restored = new RawMatrixBuffer(9, 0.35d);
        restored.restoreFromSnapshot(source.exportWeights(), source.getBoltzmannTemp());

        assertArrayEquals(source.getWeights(), restored.getWeights(), 1.0e-12d);
        assertEquals(0.42d, restored.getBoltzmannTemp(), 1.0e-12d);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawBuffer.restoredFromSnapshot"));
        assertEquals(0.42d, ((Number) TraceStore.get("cfvm.rawBuffer.boltzmannTemp")).doubleValue(), 1.0e-12d);
        assertEquals(0.42d, ((Number) TraceStore.get("cfvm.boltzmannTemp")).doubleValue(), 1.0e-12d);
        assertEquals("snapshot_restore", TraceStore.get("cfvm.tempSource"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.tempAnnealApplied"));
        assertTrue(String.valueOf(TraceStore.getAll()).contains("cfvm.rawBuffer.restoredFromSnapshot"));
    }

    @Test
    void restoreRejectsNonFiniteSnapshotAndInvalidTemperatureAtomically() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(3, 0.35d);
        buffer.updateWeight(1, 0.75d);
        double[] beforeWeights = buffer.getWeights();
        double beforeTemp = buffer.getBoltzmannTemp();

        assertThrows(IllegalArgumentException.class, () ->
                buffer.restoreFromSnapshot(
                        new double[]{0.20d, Double.NaN, 0.80d},
                        0.50d));
        assertEquals("invalid_weight", TraceStore.get("cfvm.rawBuffer.restoreSkipped"));
        assertArrayEquals(beforeWeights, buffer.getWeights(), 1.0e-12d);
        assertEquals(beforeTemp, buffer.getBoltzmannTemp(), 1.0e-12d);

        assertThrows(IllegalArgumentException.class, () ->
                buffer.restoreFromSnapshot(beforeWeights, 0.0d));
        assertEquals("invalid_temperature", TraceStore.get("cfvm.rawBuffer.restoreSkipped"));
        assertArrayEquals(beforeWeights, buffer.getWeights(), 1.0e-12d);
        assertEquals(beforeTemp, buffer.getBoltzmannTemp(), 1.0e-12d);
    }
}
