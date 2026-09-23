package com.example.lms.resilience;

import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UAW: sanity tests for aux block policy resolution & TraceStore telemetry schema.
 *
 * <p>
 * These tests are intentionally network/LLM-free and only exercise the in-memory
 * hooks (GuardContext + TraceStore).
 * </p>
 */
class AuxBlockTrackerPolicyTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void breakerOpenWinsOverCtxSignals() {
        GuardContext ctx = new GuardContext();
        ctx.setAuxDegraded(true);
        ctx.setAuxHardDown(true);
        GuardContextHolder.set(ctx);

        AuxBlockTracker.markStageBlocked(AuxBlockTracker.STAGE_QUERY_TRANSFORMER, true, ctx, "breaker open");

        assertEquals(AuxBlockedReason.BREAKER_OPEN.code(),
                TraceStore.get(AuxBlockTracker.STAGE_QUERY_TRANSFORMER_BLOCKED_KEY + ".reason"));

        Map<String, Object> ev = firstEvent();
        assertEquals(AuxBlockTracker.EVENT_TYPE_STAGE_BLOCKED, ev.get("eventType"));
        assertEquals(Boolean.TRUE, ev.get("breakerOpen"));
        assertCtxFlag(ev, "auxDegraded", true);
        assertCtxFlag(ev, "auxHardDown", true);
    }

    @Test
    void auxHardDownBeatsAuxDegraded() {
        GuardContext ctx = new GuardContext();
        ctx.setAuxDegraded(true);
        ctx.setAuxHardDown(true);
        GuardContextHolder.set(ctx);

        AuxBlockTracker.markStageBlocked(AuxBlockTracker.STAGE_QUERY_TRANSFORMER, false, ctx, "ctx hard down");

        assertEquals(AuxBlockedReason.AUX_HARD_DOWN.code(),
                TraceStore.get(AuxBlockTracker.STAGE_QUERY_TRANSFORMER_BLOCKED_KEY + ".reason"));

        Map<String, Object> ev = firstEvent();
        assertEquals(Boolean.FALSE, ev.get("breakerOpen"));
        assertCtxFlag(ev, "auxHardDown", true);
    }

    @Test
    void noSignalsFallsBackToUnknown() {
        GuardContext ctx = new GuardContext();
        GuardContextHolder.set(ctx);

        AuxBlockTracker.markStageBlocked(AuxBlockTracker.STAGE_QUERY_TRANSFORMER, false, ctx, "no signals");

        assertEquals(AuxBlockedReason.UNKNOWN.code(),
                TraceStore.get(AuxBlockTracker.STAGE_QUERY_TRANSFORMER_BLOCKED_KEY + ".reason"));

        Map<String, Object> ev = firstEvent();
        assertEquals(Boolean.FALSE, ev.get("breakerOpen"));
        assertCtxFlag(ev, "auxDegraded", false);
        assertCtxFlag(ev, "auxHardDown", false);
    }

    @Test
    void stageBlockedEventRedactsNoteAndErrorMessage() {
        String rawSecret = "test-secret-auxblocktracker-abcdefghijklmnop";
        String rawPath = "C:\\Users\\nninn\\Desktop\\secret\\aux-block.txt";
        GuardContext ctx = new GuardContext();
        GuardContextHolder.set(ctx);

        AuxBlockTracker.markStageBlocked(
                AuxBlockTracker.STAGE_QUERY_TRANSFORMER,
                false,
                ctx,
                "blocked with token=" + rawSecret,
                new IllegalStateException("failed at " + rawPath));

        Map<String, Object> ev = firstEvent();
        String serialized = String.valueOf(ev);
        assertFalse(serialized.contains(rawSecret));
        assertFalse(serialized.contains(rawPath));
        assertFalse(serialized.contains("failed at"));
        assertFalse(serialized.contains("token=" + rawSecret));
        assertTrue(ev.containsKey("note"));
        assertTrue(ev.containsKey("error.msg"));
        assertTrue(String.valueOf(ev.get("error.msg")).startsWith("hash:"), serialized);
    }

    @Test
    void stageBlockedEventRedactsBreakerKey() {
        String rawBreakerKey = "provider token=test-secret-abcdefghijklmnop";

        AuxBlockTracker.markStageBlocked(
                AuxBlockTracker.STAGE_QUERY_TRANSFORMER,
                AuxBlockedReason.BREAKER_OPEN,
                "breaker opened",
                rawBreakerKey);

        Map<String, Object> ev = firstEvent();
        String dump = String.valueOf(TraceStore.getAll());
        String eventBreakerKey = String.valueOf(ev.get("breakerKey"));
        String stickyBreakerKey = String.valueOf(TraceStore.get(AuxBlockTracker.STAGE_QUERY_TRANSFORMER_BLOCKED_KEY + ".breakerKey"));

        assertTrue(eventBreakerKey.startsWith("hash:"), eventBreakerKey);
        assertEquals(eventBreakerKey, stickyBreakerKey);
        assertFalse(dump.contains("test-secret-abcdefghijklmnop"), dump);
        assertFalse(dump.contains(rawBreakerKey), dump);
    }

    @Test
    void stageBlockedEventRedactsCallerProvidedStageName() {
        String rawStage = "queryTransformer token=private-stage-name";

        AuxBlockTracker.markStageBlocked(
                rawStage,
                AuxBlockedReason.AUX_DEGRADED,
                "stage redaction");

        Map<String, Object> ev = firstEvent();
        String dump = String.valueOf(TraceStore.getAll());
        String safeStage = String.valueOf(ev.get("stage"));

        assertTrue(safeStage.startsWith("hash_"), safeStage);
        assertFalse(dump.contains(rawStage), dump);
        assertFalse(dump.contains("private-stage-name"), dump);
    }

    @Test
    void stageBlockedEventRedactsAmbientTraceLabels() {
        String rawPipe = "pipeline token=test-secret-pipe-abcdefghijklmnop";
        String rawCallsite = "callsite C:\\Users\\nninn\\private\\QueryTransformer.java";
        TraceStore.put("pipe", rawPipe);
        TraceStore.put("qt.callsite", rawCallsite);

        AuxBlockTracker.markStageBlocked(
                AuxBlockTracker.STAGE_QUERY_TRANSFORMER,
                AuxBlockedReason.AUX_DEGRADED,
                "ambient labels");

        Map<String, Object> ev = firstEvent();
        String dump = String.valueOf(TraceStore.getAll());
        assertTrue(String.valueOf(ev.get("pipe")).startsWith("hash:"), dump);
        assertTrue(String.valueOf(ev.get("qt.callsite")).startsWith("hash:"), dump);
        assertFalse(dump.contains("test-secret-pipe-abcdefghijklmnop"), dump);
        assertFalse(dump.contains(rawPipe), dump);
        assertFalse(dump.contains(rawCallsite), dump);
    }

    @Test
    void stageNoiseOverrideEventRedactsMetaPayload() {
        String rawSecret = "test-secret-auxblocknoise-abcdefghijklmnop";
        String rawPrompt = "student private transfer instructions and prompt body";

        AuxBlockTracker.markStageNoiseOverride(
                AuxBlockTracker.STAGE_QUERY_TRANSFORMER,
                "keep noisy candidate",
                0.25d,
                Map.of(
                        "rawQuery", rawPrompt + " " + rawSecret,
                        "api_key", rawSecret,
                        "nested", Map.of("prompt", rawPrompt)));

        String dump = String.valueOf(TraceStore.getAll());
        assertFalse(dump.contains(rawSecret), dump);
        assertFalse(dump.contains(rawPrompt), dump);
        assertTrue(dump.contains(AuxBlockTracker.ANY_NOISE_EVENTS_KEY), dump);
    }

    @Test
    void auxBlockFailSoftParsersLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/resilience/AuxBlockTracker.java"));

        assertTrue(source.contains("traceSuppressed(\"auxBlock.allOpenSnapshot\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.noiseAllOpenSnapshot\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.openAtParse\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.openAtResolve\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.openUntilResolve\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.longMapRead\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxBlock.toLong\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"aux.blocked.suppressed.\" + safeStage, true);"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstEvent() {
        Object raw = TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
        assertNotNull(raw, "aux.blocked.events must exist");
        assertTrue(raw instanceof List, "aux.blocked.events must be a List");
        List<Object> events = (List<Object>) raw;
        assertFalse(events.isEmpty(), "aux.blocked.events must have at least 1 event");
        assertTrue(events.get(0) instanceof Map, "event must be a Map");
        return (Map<String, Object>) events.get(0);
    }

    @SuppressWarnings("unchecked")
    private static void assertCtxFlag(Map<String, Object> ev, String key, boolean expected) {
        Object rawFlags = ev.get("ctxFlags");
        assertNotNull(rawFlags, "ctxFlags must exist on aux.blocked.events");
        assertTrue(rawFlags instanceof Map, "ctxFlags must be a Map");
        Map<String, Object> flags = (Map<String, Object>) rawFlags;
        assertEquals(Boolean.valueOf(expected), flags.get(key), "ctxFlags." + key);
    }
}
