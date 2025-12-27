package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class AuxBlockTrackerPolicyTest {

    @AfterEach
    void teardown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void reasonPriority_breakerOpenAlwaysWins() {
        GuardContext ctx = new GuardContext();
        ctx.setAuxHardDown(true);
        ctx.setAuxDegraded(true);
        ctx.setBypassMode(true);

        AuxBlockedReason resolved = AuxBlockTracker.resolveReason(true, ctx);
        assertEquals(AuxBlockedReason.BREAKER_OPEN, resolved);
    }

    @Test
    void reasonPriority_ctxHardDownBeatsDegradedStrikeCompression() {
        GuardContext ctx = new GuardContext();
        ctx.setAuxHardDown(true);
        ctx.setAuxDegraded(true);
        ctx.setStrikeMode(true);
        ctx.setCompressionMode(true);

        assertEquals(AuxBlockedReason.AUX_HARD_DOWN, AuxBlockedReason.fromContext(ctx));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_keysArePutOnce_eventsAppend_andEventsContainEventTypeAndBreakerKey() {
        TraceStore.clear();

        String stage = "keywordSelection";
        String breakerKey1 = "breaker:key-1";
        String breakerKey2 = "breaker:key-2";

        AuxBlockTracker.markStageBlocked(stage, AuxBlockedReason.AUX_DEGRADED, "first", breakerKey1);
        AuxBlockTracker.markStageBlocked(stage, AuxBlockedReason.AUX_HARD_DOWN, "second", breakerKey2);

        // Global keys should reflect the first block (putIfAbsent) and last event (last).
        assertEquals(Boolean.TRUE, TraceStore.get(AuxBlockTracker.ANY_BLOCKED_KEY));
        assertEquals(stage, TraceStore.get(AuxBlockTracker.ANY_BLOCKED_STAGE_KEY));
        assertEquals(AuxBlockedReason.AUX_DEGRADED.code(), TraceStore.get(AuxBlockTracker.ANY_BLOCKED_REASON_KEY));

        Object eventsObj = TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
        assertNotNull(eventsObj);
        assertTrue(eventsObj instanceof List);

        List<Object> events = (List<Object>) eventsObj;
        assertEquals(2, events.size());

        Map<String, Object> first = (Map<String, Object>) events.get(0);
        assertEquals(AuxBlockTracker.EVENT_TYPE_STAGE_BLOCKED, first.get("eventType"));
        assertEquals(stage, first.get("stage"));
        assertEquals(AuxBlockedReason.AUX_DEGRADED.code(), first.get("reason"));
        assertEquals(breakerKey1, first.get("breakerKey"));
        assertEquals(Boolean.FALSE, first.get("breakerOpen"));
        assertTrue(first.get("ctxFlags") instanceof AuxCtxFlagsSnapshot);
        AuxCtxFlagsSnapshot firstCtx = (AuxCtxFlagsSnapshot) first.get("ctxFlags");
        assertFalse(firstCtx.auxDegraded());
        assertFalse(firstCtx.auxHardDown());

        Map<String, Object> second = (Map<String, Object>) events.get(1);
        assertEquals(AuxBlockTracker.EVENT_TYPE_STAGE_BLOCKED, second.get("eventType"));
        assertEquals(stage, second.get("stage"));
        assertEquals(AuxBlockedReason.AUX_HARD_DOWN.code(), second.get("reason"));
        assertEquals(breakerKey2, second.get("breakerKey"));
        assertEquals(Boolean.FALSE, second.get("breakerOpen"));
        assertTrue(second.get("ctxFlags") instanceof AuxCtxFlagsSnapshot);

        // Stage-scoped first keys should remain stable (putIfAbsent)
        assertEquals(Boolean.TRUE, TraceStore.get("aux.keywordSelection.blocked"));
        assertEquals(AuxBlockedReason.AUX_DEGRADED.code(), TraceStore.get("aux.keywordSelection.blocked.reason"));
        assertEquals(AuxBlockTracker.EVENT_TYPE_STAGE_BLOCKED, TraceStore.get("aux.keywordSelection.blocked.eventType"));
        assertEquals(breakerKey1, TraceStore.get("aux.keywordSelection.blocked.breakerKey"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_eventContainsCtxFlagsSnapshotAndBreakerOpenFlagWhenProvided() {
        TraceStore.clear();

        GuardContext ctx = new GuardContext();
        ctx.setAuxDegraded(true);
        ctx.setAuxHardDown(false);
        ctx.setBypassMode(true);
        ctx.setBypassReason("test-bypass-reason");

        String stage = "queryTransformer";
        AuxBlockTracker.markStageBlocked(stage, false, ctx, "ctx degraded");

        Object eventsObj = TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
        assertNotNull(eventsObj);
        assertTrue(eventsObj instanceof List);

        List<Object> events = (List<Object>) eventsObj;
        assertEquals(1, events.size());

        Map<String, Object> evt = (Map<String, Object>) events.get(0);
        assertEquals(stage, evt.get("stage"));
        assertEquals(AuxBlockedReason.AUX_DEGRADED.code(), evt.get("reason"));
        assertEquals(Boolean.FALSE, evt.get("breakerOpen"));

        assertTrue(evt.get("ctxFlags") instanceof AuxCtxFlagsSnapshot);
        AuxCtxFlagsSnapshot flags = (AuxCtxFlagsSnapshot) evt.get("ctxFlags");
        assertTrue(flags.auxDegraded());
        assertFalse(flags.auxHardDown());
        assertTrue(flags.bypassMode());
        assertEquals("test-bypass-reason", flags.bypassReason());
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_breakerOpenEventContainsBreakerOpenAtTimestamp() {
        TraceStore.clear();

        String stage = "disambiguation";
        String breakerKey = "breaker:disambiguation";

        long nowMs = System.currentTimeMillis();
        long openSinceMs = nowMs - 42_000;
        long openUntilMs = nowMs + 20_000;

        Map<String, Long> openAtByKey = new ConcurrentHashMap<>();
        openAtByKey.put(breakerKey, openSinceMs);
        TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, openAtByKey);

        Map<String, Long> openUntilByKey = new ConcurrentHashMap<>();
        openUntilByKey.put(breakerKey, openUntilMs);
        TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, openUntilByKey);
        TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_LAST_KEY, openUntilMs);

        AuxBlockTracker.markStageBlocked(stage, true, breakerKey, null, "breaker-open");

        Object eventsObj = TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
        assertNotNull(eventsObj);
        assertTrue(eventsObj instanceof List);

        List<Object> events = (List<Object>) eventsObj;
        assertEquals(1, events.size());

        Map<String, Object> evt = (Map<String, Object>) events.get(0);
        assertEquals(Boolean.TRUE, evt.get("breakerOpen"));
        assertEquals(breakerKey, evt.get("breakerKey"));

        assertNotNull(evt.get("breakerOpenAt"));
        assertNotNull(evt.get("breakerOpenAtMs"));
        assertTrue(evt.get("breakerOpenAtMs") instanceof Number);

        assertEquals(openSinceMs, ((Number) evt.get("breakerOpenAtMs")).longValue());
        assertEquals(openSinceMs, ((Number) evt.get("breakerOpenSinceMs")).longValue());

        assertEquals(openUntilMs, ((Number) evt.get("breakerOpenUntilMs")).longValue());
        assertNotNull(evt.get("breakerOpenUntil"));
        assertTrue(evt.get("remainingOpenMs") instanceof Number);
        long remaining = ((Number) evt.get("remainingOpenMs")).longValue();
        assertTrue(remaining >= 0);
        assertTrue(remaining <= 20_000);

        assertTrue(evt.get("openWindowMs") instanceof Number);
        assertEquals(openUntilMs - openSinceMs, ((Number) evt.get("openWindowMs")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_allOpenBreakersSnapshotPresentWhenMultipleBreakersOpen() {
        TraceStore.clear();

        long baseMs = System.currentTimeMillis();

        String breakerA = "breakerA";
        String breakerB = "breakerB";

        long aSince = baseMs - 5_000L;
        long bSince = baseMs - 10_000L;
        long aUntil = baseMs + 60_000L;
        long bUntil = baseMs + 120_000L;

        Map<String, Long> openAtByKey = new ConcurrentHashMap<>();
        openAtByKey.put(breakerA, aSince);
        openAtByKey.put(breakerB, bSince);

        Map<String, Long> openUntilByKey = new ConcurrentHashMap<>();
        openUntilByKey.put(breakerA, aUntil);
        openUntilByKey.put(breakerB, bUntil);

        TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, openAtByKey);
        TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, openUntilByKey);

        AuxBlockTracker.markStageBlocked("disambiguation", true, breakerA, null, "multi-open");

        Object eventsObj = TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
        assertNotNull(eventsObj);
        assertTrue(eventsObj instanceof List);

        List<Object> events = (List<Object>) eventsObj;
        assertEquals(1, events.size());

        Map<String, Object> evt = (Map<String, Object>) events.get(0);
        assertEquals(Boolean.TRUE, evt.get("breakerOpen"));
        assertEquals(breakerA, evt.get("breakerKey"));

        assertTrue(evt.get("allOpenBreakersCount") instanceof Number);
        assertEquals(2, ((Number) evt.get("allOpenBreakersCount")).intValue());

        assertTrue(evt.get("allOpenBreakersKeys") instanceof List);
        List<Object> keys = (List<Object>) evt.get("allOpenBreakersKeys");
        assertEquals(List.of(breakerA, breakerB), keys);

        assertTrue(evt.get("allOpenBreakersSnapshot") instanceof Map);
        Map<String, Object> snap = (Map<String, Object>) evt.get("allOpenBreakersSnapshot");
        assertEquals(2, snap.size());

        assertTrue(snap.get(breakerA) instanceof AuxBreakerOpenSnapshot);
        AuxBreakerOpenSnapshot a = (AuxBreakerOpenSnapshot) snap.get(breakerA);
        assertEquals(breakerA, a.breakerKey());
        assertEquals(aSince, a.openSinceMs());
        assertEquals(aUntil, a.openUntilMs());
        assertEquals(aUntil - aSince, a.openWindowMs());
        assertTrue(a.remainingOpenMs() > 0);

        assertTrue(snap.get(breakerB) instanceof AuxBreakerOpenSnapshot);
        AuxBreakerOpenSnapshot b = (AuxBreakerOpenSnapshot) snap.get(breakerB);
        assertEquals(breakerB, b.breakerKey());
        assertEquals(bSince, b.openSinceMs());
        assertEquals(bUntil, b.openUntilMs());
        assertEquals(bUntil - bSince, b.openWindowMs());
        assertTrue(b.remainingOpenMs() > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_allOpenBreakersSnapshotPolicy_alwaysIncludesSingleOpenBreaker() {
        String prop = AuxBlockTracker.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY;
        String prev = System.getProperty(prop);
        try {
            System.setProperty(prop, "ALWAYS");

            TraceStore.clear();

            long baseMs = System.currentTimeMillis();
            String breakerA = "breakerA";

            Map<String, Long> openAtByKey = new ConcurrentHashMap<>();
            openAtByKey.put(breakerA, baseMs - 5_000L);

            Map<String, Long> openUntilByKey = new ConcurrentHashMap<>();
            openUntilByKey.put(breakerA, baseMs + 60_000L);

            TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, openAtByKey);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, openUntilByKey);

            // breakerOpen=false, but policy=ALWAYS -> snapshot should still be attached
            AuxBlockTracker.markStageBlocked("queryTransformer", false, breakerA, null, "policy-always");

            List<Object> events = (List<Object>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(events);
            assertEquals(1, events.size());

            Map<String, Object> evt = (Map<String, Object>) events.get(0);
            assertEquals(1, ((Number) evt.get("allOpenBreakersCount")).intValue());
            assertTrue(evt.get("allOpenBreakersSnapshot") instanceof Map);
        } finally {
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceStore_allOpenBreakersSnapshotPolicy_breakerOpenOnly() {
        String prop = AuxBlockTracker.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY;
        String prev = System.getProperty(prop);
        try {
            System.setProperty(prop, "BREAKER_OPEN_ONLY");

            TraceStore.clear();

            long baseMs = System.currentTimeMillis();
            String breakerA = "breakerA";

            Map<String, Long> openAtByKey = new ConcurrentHashMap<>();
            openAtByKey.put(breakerA, baseMs - 5_000L);

            Map<String, Long> openUntilByKey = new ConcurrentHashMap<>();
            openUntilByKey.put(breakerA, baseMs + 60_000L);

            TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, openAtByKey);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, openUntilByKey);

            AuxBlockTracker.markStageBlocked("queryTransformer", false, breakerA, null, "policy-breakerOpenOnly-false");
            AuxBlockTracker.markStageBlocked("queryTransformer", true, breakerA, null, "policy-breakerOpenOnly-true");

            List<Object> events = (List<Object>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(events);
            assertEquals(2, events.size());

            Map<String, Object> evt0 = (Map<String, Object>) events.get(0);
            Map<String, Object> evt1 = (Map<String, Object>) events.get(1);

            assertEquals(Boolean.FALSE, evt0.get("breakerOpen"));
            assertNull(evt0.get("allOpenBreakersSnapshot"));

            assertEquals(Boolean.TRUE, evt1.get("breakerOpen"));
            assertTrue(evt1.get("allOpenBreakersSnapshot") instanceof Map);
        } finally {
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }
}
