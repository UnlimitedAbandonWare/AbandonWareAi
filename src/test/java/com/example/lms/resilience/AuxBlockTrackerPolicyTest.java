package com.example.lms.resilience;

import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
