package com.example.lms.trace;

import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceMemoryFingerprintProbeTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordsCheckpointDeltaAndRoutesLoaderStarvationWithoutLeakingRawMemory() {
        DebugEventStore debugStore = new DebugEventStore();
        RawMatrixBuffer rawMatrixBuffer = new RawMatrixBuffer();
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(debugStore),
                provider(rawMatrixBuffer),
                provider(RecoveryPolicy.fromYaml("""
                        map:
                          data: fallback
                          logic: degrade
                          policy: escalate
                        """)));

        String privateMemory = "private student memory payload must not leak";
        TraceStore.put("trace.breadcrumb.memory.loader", true);
        TraceMemoryFingerprintProbe.Checkpoint raw = probe.checkpoint(
                "raw_snapshot",
                "unit-test",
                Map.of("memoryCtx", privateMemory, "supabaseShadow", Map.of("rowCount", 1)));

        TraceStore.put("prompt.memory.compressor.reason", "all_lines_dropped");
        TraceStore.put("prompt.memory.compressor.inputLen", privateMemory.length());
        TraceStore.put("prompt.memory.compressor.outputLen", 0);
        TraceMemoryFingerprintProbe.Checkpoint refined = probe.checkpoint(
                "first_refinement",
                "unit-test",
                Map.of("memoryCtx", "", "supabaseShadow", Map.of("rowCount", 0)));

        assertFalse(raw.triggered());
        assertTrue(refined.triggered());
        assertEquals("loader_starvation", refined.triggerReason());
        assertEquals("FALLBACK", TraceStore.get("traceMemory.recovery.action"));
        assertEquals("retry_failsoft", TraceStore.get("traceMemory.recovery.route"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.recovery.failSoft"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.recovery.retry"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.suspectPayload.isolated"));
        assertEquals("retry_failsoft", TraceStore.get("traceMemory.suspectPayload.route"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.cfvm.offered"));
        assertEquals(1, rawMatrixBuffer.size());
        assertTrue(String.valueOf(TraceStore.get("traceMemory.fingerprint.current")).startsWith("hash:"));
        assertNotEquals(privateMemory, TraceStore.get("traceMemory.fingerprint.current"));

        String publicPayload = TraceStore.getByPrefix("traceMemory.").toString()
                + debugStore.list(20).toString();
        assertFalse(publicPayload.contains(privateMemory), publicPayload);
        assertTrue(debugStore.list(20).stream()
                .map(DebugEvent::fingerprint)
                .anyMatch(SafeRedactor.hashValue(
                        "trace_memory:loader_starvation:FALLBACK")::equals));
    }

    @Test
    void contextContaminationEscalatesThroughBreakGuardAndQuarantineSignal() {
        DebugEventStore debugStore = new DebugEventStore();
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(debugStore),
                provider(new RawMatrixBuffer()),
                provider(RecoveryPolicy.fromYaml("""
                        map:
                          policy: escalate
                        """)));

        TraceStore.put("prompt.memory.compressor.reason", "history_context_contamination");
        TraceMemoryFingerprintProbe.Checkpoint checkpoint = probe.checkpoint(
                "second_refinement",
                "unit-test",
                Map.of("memoryCtx", "build log contamination marker"));

        assertTrue(checkpoint.triggered());
        assertEquals("context_contamination", checkpoint.triggerReason());
        assertEquals("ESCALATE", TraceStore.get("traceMemory.recovery.action"));
        assertEquals("quarantine_retry_failsoft", TraceStore.get("traceMemory.recovery.route"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.recovery.quarantine"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.suspectPayload.isolated"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.suspectPayload.quarantine"));
        assertEquals("BREAK", TraceStore.get("traceMemory.errorBreak.risk"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.errorBreak.cacheOnly"));
    }

    @Test
    void cleanCheckpointClearsStaleRecoveryAndSuspectPayloadState() {
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(new DebugEventStore()),
                provider(new RawMatrixBuffer()),
                provider(RecoveryPolicy.fromYaml("""
                        map:
                          data: fallback
                        """)));

        probe.checkpoint("raw_snapshot", "unit-test", Map.of("memoryCtx", "seed memory"));
        TraceStore.put("prompt.memory.compressor.reason", "all_lines_dropped");
        TraceMemoryFingerprintProbe.Checkpoint triggered = probe.checkpoint(
                "first_refinement",
                "unit-test",
                Map.of("memoryCtx", ""));

        assertTrue(triggered.triggered());
        assertEquals("FALLBACK", TraceStore.get("traceMemory.recovery.action"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.suspectPayload.isolated"));

        TraceStore.put("prompt.memory.compressor.reason", null);
        TraceMemoryFingerprintProbe.Checkpoint clean = probe.checkpoint(
                "second_refinement",
                "unit-test",
                Map.of("memoryCtx", "stable memory"));

        assertFalse(clean.triggered());
        assertEquals(Boolean.FALSE, TraceStore.get("traceMemory.recoveryMode"));
        assertEquals(Boolean.FALSE, TraceStore.get("traceMemory.cfvm.offered"));
        assertNull(TraceStore.get("traceMemory.recovery.action"));
        assertNull(TraceStore.get("traceMemory.recovery.routeDecision"));
        assertNull(TraceStore.get("traceMemory.suspectPayload.isolated"));
        assertNull(TraceStore.get("traceMemory.suspectPayload.fingerprint"));
    }

    @Test
    void publishesStageScopedVirtualCheckpointsForTraceMemoryDeltas() {
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(new DebugEventStore()),
                provider(new RawMatrixBuffer()),
                provider(new RecoveryPolicy()));

        TraceMemoryFingerprintProbe.Checkpoint raw = probe.checkpoint(
                "raw_snapshot",
                "unit-test",
                Map.of(
                        "phase", "memory_loader_raw",
                        "memoryCtx", "seed memory"));
        TraceMemoryFingerprintProbe.Checkpoint refined = probe.checkpoint(
                "first_refinement",
                "unit-test",
                Map.of(
                        "phase", "memory_loader_after_recent_filter",
                        "memoryCtx", "seed memory after filter"));

        assertEquals("traceMemory.virtualCheckpoint.first_refinement",
                TraceStore.get("traceMemory.virtualCheckpoint.latestKey"));
        assertEquals("raw_snapshot", TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.stage"));
        assertEquals("memory.loader.raw", TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.phase"));
        assertEquals(raw.fingerprint(), TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.fingerprint"));
        assertEquals(0, TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.delta.addedCount"));
        assertEquals("first_refinement", TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.stage"));
        assertEquals("memory.loader.after_recent_filter",
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.phase"));
        assertEquals(refined.fingerprint(),
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.fingerprint"));
        assertEquals(raw.fingerprint(),
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.previousFingerprint"));
        assertEquals(refined.addedCount(),
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.delta.addedCount"));
        assertEquals(refined.changedCount(),
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.delta.changedCount"));
        assertEquals(refined.droppedBreadcrumbCount(),
                TraceStore.get("traceMemory.virtualCheckpoint.first_refinement.delta.droppedBreadcrumbCount"));
    }

    @Test
    void publishesSupabaseShadowSnapshotStateWithoutLeakingRawPayload() {
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(new DebugEventStore()),
                provider(new RawMatrixBuffer()),
                provider(new RecoveryPolicy()));

        String privateShadowPayload = "private supabase shadow memory row must not leak";
        probe.checkpoint(
                "raw_snapshot",
                "unit-test",
                Map.of(
                        "memoryCtx", "seed memory",
                        "supabaseShadow", Map.of(
                                "schemaSnapshotArtifact", "present",
                                "projectScopeStatus", "project_ref_missing",
                                "schemaSnapshotAvailable", false,
                                "schemaSnapshotComplete", false,
                                "readOnly", true,
                                "mutationAllowed", false,
                                "evidenceNeededCount", 3,
                                "nextAction", "set_SUPABASE_PROJECT_REF",
                                "privatePayload", privateShadowPayload)));

        assertEquals("present", TraceStore.get("traceMemory.rawSnapshot.supabaseShadowArtifact"));
        assertEquals("project_ref_missing", TraceStore.get("traceMemory.rawSnapshot.supabaseShadowProjectScope"));
        assertEquals(Boolean.FALSE, TraceStore.get("traceMemory.rawSnapshot.supabaseShadowAvailable"));
        assertEquals(Boolean.FALSE, TraceStore.get("traceMemory.rawSnapshot.supabaseShadowComplete"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.rawSnapshot.supabaseShadowReadOnly"));
        assertEquals(Boolean.FALSE, TraceStore.get("traceMemory.rawSnapshot.supabaseShadowMutationAllowed"));
        assertEquals(3, TraceStore.get("traceMemory.rawSnapshot.supabaseShadowEvidenceNeededCount"));
        assertEquals("set_SUPABASE_PROJECT_REF", TraceStore.get("traceMemory.rawSnapshot.supabaseShadowNextAction"));
        assertEquals("present", TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.supabaseShadowArtifact"));
        assertEquals("project_ref_missing",
                TraceStore.get("traceMemory.virtualCheckpoint.raw_snapshot.supabaseShadowProjectScope"));
        assertFalse(TraceStore.getByPrefix("traceMemory.").toString().contains(privateShadowPayload));
    }

    @Test
    void providerFailureDuringSnapshotLeavesRedactedBreadcrumb() {
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                throwingProvider("debug-store secret should not leak"),
                provider(new RawMatrixBuffer()),
                provider(new RecoveryPolicy()));

        TraceMemoryFingerprintProbe.Checkpoint checkpoint = probe.checkpoint(
                "raw_snapshot",
                "unit-test",
                Map.of("memoryCtx", "seed memory"));

        assertFalse(checkpoint.triggered());
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.provider.suppressed"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.provider.suppressed.debugEventStore"));
        assertEquals("debugEventStore", TraceStore.get("traceMemory.provider.suppressed.stage"));
        assertEquals("IllegalStateException",
                TraceStore.get("traceMemory.provider.suppressed.debugEventStore.errorType"));
        assertTrue(String.valueOf(
                TraceStore.get("traceMemory.provider.suppressed.debugEventStore.errorHash")).startsWith("hash:"));
        assertEquals(0, TraceStore.get("traceMemory.rawSnapshot.debugCount"));
        assertFalse(TraceStore.getByPrefix("traceMemory.").toString().contains("secret should not leak"));
    }

    @Test
    void invalidTraceNumericValueLeavesRedactedNumberSuppressionBreadcrumb() {
        TraceMemoryFingerprintProbe probe = new TraceMemoryFingerprintProbe(
                provider(new DebugEventStore()),
                provider(new RawMatrixBuffer()),
                provider(new RecoveryPolicy()));

        TraceStore.put("context.candidates.raw", "not-a-number private memory should not leak");
        TraceStore.put("context.candidates.afterFilter", 0);
        TraceMemoryFingerprintProbe.Checkpoint checkpoint = probe.checkpoint(
                "first_refinement",
                "unit-test",
                Map.of("memoryCtx", "seed memory"));

        assertFalse(checkpoint.triggered());
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.number.suppressed"));
        assertEquals("toLong", TraceStore.get("traceMemory.number.suppressed.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.number.suppressed.toLong"));
        assertEquals("NumberFormatException",
                TraceStore.get("traceMemory.number.suppressed.toLong.errorType"));
        assertTrue(String.valueOf(
                TraceStore.get("traceMemory.number.suppressed.toLong.errorHash")).startsWith("hash:"));
        assertFalse(TraceStore.getByPrefix("traceMemory.").toString().contains("private memory should not leak"));
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
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
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

    private static <T> ObjectProvider<T> throwingProvider(String message) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new IllegalStateException(message);
            }

            @Override
            public T getIfAvailable() {
                throw new IllegalStateException(message);
            }

            @Override
            public T getIfUnique() {
                throw new IllegalStateException(message);
            }

            @Override
            public T getObject() {
                throw new IllegalStateException(message);
            }

            @Override
            public Iterator<T> iterator() {
                return List.<T>of().iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
