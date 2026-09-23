package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CfvmSnapshotServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void persistSnapshotSavesWeightsWithoutRawSessionValue() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(9, 0.35d);
        buffer.updateWeight(4, 2.0d);

        CfvmSnapshotRepository repository = mock(CfvmSnapshotRepository.class);
        CfvmSnapshotService service = new CfvmSnapshotService(provider(buffer), provider(repository));

        service.persistSnapshot("secret-session-token");

        ArgumentCaptor<CfvmSnapshot> captor = ArgumentCaptor.forClass(CfvmSnapshot.class);
        verify(repository).save(captor.capture());
        CfvmSnapshot saved = captor.getValue();

        assertEquals(9, saved.getBufferSize());
        assertEquals(4, saved.getDominantSlot());
        assertEquals(0.35d, saved.getBoltzmannTemp(), 1.0e-12d);
        assertFalse(saved.getWeightsJson().contains("secret-session-token"));
        assertNotEquals("secret-session-token", saved.getSessionHash());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.snapshot.saved"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.snapshot.vectorWrite.enabled"));
        assertEquals("jpa_snapshot_only", TraceStore.get("cfvm.snapshot.vectorWrite.skipped"));
    }

    @Test
    void restoreOnStartupLoadsLatestSnapshotIntoBuffer() {
        RawMatrixBuffer source = new RawMatrixBuffer(9, 0.35d);
        source.updateWeight(2, 1.5d);

        CfvmSnapshot snapshot = new CfvmSnapshot();
        snapshot.setWeightsJson("[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9]");
        snapshot.setBoltzmannTemp(0.45d);

        RawMatrixBuffer target = new RawMatrixBuffer(9, 0.35d);
        CfvmSnapshotRepository repository = mock(CfvmSnapshotRepository.class);
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(snapshot));

        CfvmSnapshotService service = new CfvmSnapshotService(provider(target), provider(repository));
        service.restoreOnStartup();

        assertArrayEquals(new double[] {0.1d, 0.2d, 0.3d, 0.4d, 0.5d, 0.6d, 0.7d, 0.8d, 0.9d},
                target.getWeights(), 1.0e-12d);
        assertEquals(0.45d, target.getBoltzmannTemp(), 1.0e-12d);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.snapshot.restored"));
    }

    @Test
    void serviceConstructorIsExplicitlyAutowiredForSpringBootWiring() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/cfvm/CfvmSnapshotService.java"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("@Autowired\n    public CfvmSnapshotService("));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
