package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CfvmSnapshotRestoreOutcomeTest {
    private static final String VALID_WEIGHTS = "[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9]";
    private final RawMatrixBuffer buffer = new RawMatrixBuffer(9, 0.35d);
    private final CfvmSnapshotRepository repository = mock(CfvmSnapshotRepository.class);
    private final CfvmSnapshotService service = new CfvmSnapshotService(provider(buffer), provider(repository));

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0.1,0.2,0.3]", "null"})
    void incompatibleWeightsPreserveStateAndNeverReportSuccess(String weights) {
        double[] before = buffer.exportWeights();
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(snapshot(weights, 0.7d)));

        service.restoreOnStartup();

        assertArrayEquals(before, buffer.exportWeights());
        assertEquals(0.35d, buffer.getBoltzmannTemp());
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.snapshot.restored"));
        assertEquals("weight_count_mismatch", TraceStore.get("cfvm.snapshot.restore.skipped"));
        assertEquals("weight_count_mismatch", TraceStore.get("cfvm.rawBuffer.restoreSkipped"));
        assertNull(TraceStore.get("cfvm.snapshot.restored.id"));
    }

    @Test
    void missingSnapshotAfterSuccessClearsPreviousOutcome() {
        when(repository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.of(snapshot(VALID_WEIGHTS, 0.7d)), Optional.empty());
        service.restoreOnStartup();
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.snapshot.restored"));
        assertEquals(42L, TraceStore.get("cfvm.snapshot.restored.id"));
        double[] restored = buffer.exportWeights();

        service.restoreOnStartup();

        assertArrayEquals(restored, buffer.exportWeights());
        assertEquals(0.7d, buffer.getBoltzmannTemp());
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.snapshot.restored"));
        assertNull(TraceStore.get("cfvm.snapshot.restored.id"));
        assertEquals("no_previous_snapshot", TraceStore.get("cfvm.snapshot.restore.skipped"));
    }

    @Test
    void invalidTemperatureAfterSuccessRejectsAtomicallyAndClearsSuccess() {
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(
                Optional.of(snapshot(VALID_WEIGHTS, 0.7d)),
                Optional.of(snapshot("[9,8,7,6,5,4,3,2,1]", Double.NaN)));
        service.restoreOnStartup();
        double[] restored = buffer.exportWeights();

        service.restoreOnStartup();

        assertArrayEquals(restored, buffer.exportWeights());
        assertEquals(0.7d, buffer.getBoltzmannTemp());
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.snapshot.restored"));
        assertNull(TraceStore.get("cfvm.snapshot.restored.id"));
        assertEquals("IllegalArgumentException", TraceStore.get("cfvm.snapshot.restore.error"));
    }

    @Test
    void successfulRestoreClearsEarlierSkipAndParseFailure() {
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty(),
                Optional.of(snapshot("invalid-json", 0.7d)), Optional.of(snapshot(VALID_WEIGHTS, 0.7d)));
        service.restoreOnStartup();
        service.restoreOnStartup();
        assertEquals("json_parse", TraceStore.get("cfvm.snapshot.restore.error"));

        service.restoreOnStartup();

        assertArrayEquals(new double[]{0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9}, buffer.exportWeights());
        assertEquals(0.7d, buffer.getBoltzmannTemp());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.snapshot.restored"));
        assertNull(TraceStore.get("cfvm.snapshot.restore.skipped"));
        assertNull(TraceStore.get("cfvm.snapshot.restore.error"));
    }

    private static CfvmSnapshot snapshot(String weights, double temperature) {
        CfvmSnapshot snapshot = new CfvmSnapshot();
        snapshot.setId(42L);
        snapshot.setWeightsJson(weights);
        snapshot.setBoltzmannTemp(temperature);
        return snapshot;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
