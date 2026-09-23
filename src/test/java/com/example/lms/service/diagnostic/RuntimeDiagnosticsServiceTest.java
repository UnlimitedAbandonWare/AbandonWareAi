package com.example.lms.service.diagnostic;

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import ai.abandonware.nova.orch.storage.PendingMemoryEvent;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDiagnosticsServiceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void runtimeSnapshotDoesNotExposeLegacyImageGatewayAndKeepsCoreSections() {
        RuntimeDiagnosticsService service = new RuntimeDiagnosticsService(
                provider(null),
                provider(null),
                provider(null));

        Map<String, Object> snapshot = service.snapshot();

        assertTrue(snapshot.containsKey("timestamp"));
        assertTrue(snapshot.containsKey("vectorStore"));
        assertTrue(snapshot.containsKey("nightmareBreaker"));
        assertTrue(snapshot.containsKey("outbox"));
        assertFalse(snapshot.containsKey("com" + "fyGateway"));
        assertEquals(Map.of("available", false), snapshot.get("vectorStore"));
        assertEquals(Map.of("available", false), snapshot.get("nightmareBreaker"));
        assertEquals(Map.of("available", false), snapshot.get("outbox"));
    }

    @Test
    void runtimeSnapshotRedactsOutboxStatsPath() {
        String rawPath = "C:/private/outbox/session-secret/path";
        RuntimeDiagnosticsService service = new RuntimeDiagnosticsService(
                provider(null),
                provider(null),
                provider(outboxWithStats(rawPath)));

        Map<String, Object> snapshot = service.snapshot();
        String rendered = String.valueOf(snapshot);

        assertFalse(rendered.contains(rawPath));
        assertTrue(snapshot.get("outbox") instanceof Map<?, ?>);
        Map<?, ?> outbox = (Map<?, ?>) snapshot.get("outbox");
        assertFalse(outbox.containsKey("path"));
        assertTrue(String.valueOf(outbox.get("pathHash")).startsWith("hash:"));
        assertEquals(rawPath.length(), outbox.get("pathLength"));
    }

    private static DegradedStorageWithAck outboxWithStats(String path) {
        return new DegradedStorageWithAck() {
            @Override
            public List<ClaimedPending> claim(int max) {
                return List.of();
            }

            @Override
            public void ack(String token) {
            }

            @Override
            public void release(String token) {
            }

            @Override
            public void nack(String token, String error) {
            }

            @Override
            public OutboxStats stats() {
                return new OutboxStats(true, "file", path, 1, 0, 42L,
                        Instant.EPOCH, Instant.EPOCH, 100, 1024L, 60L, 30L,
                        1L, 0L, 0L, 0L, 0L, 0L, 0L);
            }

            @Override
            public OutboxSweepResult sweep() {
                return new OutboxSweepResult(0L, 0, 0, 0, 0, 0L, 0L);
            }

            @Override
            public void putPending(PendingMemoryEvent event) {
            }

            @Override
            public List<PendingMemoryEvent> drain(int max) {
                return List.of();
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
