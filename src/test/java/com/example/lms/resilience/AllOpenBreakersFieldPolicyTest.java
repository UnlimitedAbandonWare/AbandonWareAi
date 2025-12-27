package com.example.lms.resilience;

import com.example.lms.infra.resilience.AllOpenBreakersKeysPolicy;
import com.example.lms.infra.resilience.AllOpenBreakersSnapshotPolicy;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AllOpenBreakersFieldPolicyTest {

    @Test
    void keysAlwaysIncluded_evenWhenKeysPolicyMisconfigured_whenNoOpenBreakers() {
        String prevKeysPol = System.getProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY);
        String prevSnapPol = System.getProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY);
        try {
            // Misconfiguration (should be ignored): keys are always included by code-level policy.
            System.setProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, "NEVER");
            System.setProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, "MULTI_OPEN_ONLY");

            TraceStore.clear();

            GuardContext ctx = new GuardContext();
            ctx.setAuxDegraded(true);

            AuxBlockTracker.markStageBlocked("KeywordSelection", false, ctx, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(events);
            assertEquals(1, events.size());

            Map<String, Object> ev = events.get(0);
            assertTrue(ev.containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_COUNT));
            assertTrue(ev.containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_KEYS));

            assertEquals(0, ((Number) ev.get(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_COUNT)).intValue());

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) ev.get(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_KEYS);
            assertNotNull(keys);
            assertTrue(keys.isEmpty());

            // Snapshot is policy-controlled; MULTI_OPEN_ONLY should not include it when openCount == 0
            assertFalse(ev.containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_SNAPSHOT));
        } finally {
            restore(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, prevKeysPol);
            restore(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, prevSnapPol);
            TraceStore.clear();
        }
    }

    @Test
    void snapshotPolicy_multiOpenOnly_includesSnapshot_whenMultipleOpenBreakers() {
        String prevKeysPol = System.getProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY);
        String prevSnapPol = System.getProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY);
        try {
            System.setProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, "ALWAYS");
            System.setProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, "MULTI_OPEN_ONLY");

            TraceStore.clear();

            long now = System.currentTimeMillis();
            Map<String, Long> until = new HashMap<>();
            until.put("b1", now + 60_000L);
            until.put("b2", now + 120_000L);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, until);

            Map<String, Long> since = new HashMap<>();
            since.put("b1", now - 5_000L);
            since.put("b2", now - 3_000L);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, since);

            GuardContext ctx = new GuardContext();
            ctx.setAuxDegraded(true);

            AuxBlockTracker.markStageBlocked("QueryTransformer", false, ctx, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(events);
            assertEquals(1, events.size());

            Map<String, Object> ev = events.get(0);

            assertEquals(2, ((Number) ev.get(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_COUNT)).intValue());

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) ev.get(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_KEYS);
            assertNotNull(keys);
            assertEquals(2, keys.size());
            assertTrue(keys.contains("b1"));
            assertTrue(keys.contains("b2"));

            // MULTI_OPEN_ONLY should include the snapshot when count > 1
            assertTrue(ev.containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_SNAPSHOT));

            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) ev.get(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_SNAPSHOT);
            assertNotNull(snap);
            assertEquals(2, snap.size());
            assertTrue(snap.containsKey("b1"));
            assertTrue(snap.containsKey("b2"));
        } finally {
            restore(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, prevKeysPol);
            restore(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, prevSnapPol);
            TraceStore.clear();
        }
    }

    @Test
    void snapshotForcedOnBreakerOpen_evenWhenSnapshotPolicyNever() {
        String prevKeysPol = System.getProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY);
        String prevSnapPol = System.getProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY);
        try {
            // Misconfiguration: both policies set to NEVER. Keys should still be attached
            // and snapshot should still be forced on breakerOpen=true.
            System.setProperty(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, "NEVER");
            System.setProperty(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, "NEVER");

            long now = System.currentTimeMillis();
            Map<String, Long> until = new HashMap<>();
            until.put("b1", now + 60_000L);
            until.put("b2", now + 120_000L);
            Map<String, Long> since = new HashMap<>();
            since.put("b1", now - 5_000L);
            since.put("b2", now - 3_000L);

            // Case A: breakerOpen=false (even though there are open breakers elsewhere)
            TraceStore.clear();
            TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, until);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, since);

            GuardContext ctxA = new GuardContext();
            ctxA.setAuxDegraded(true);
            AuxBlockTracker.markStageBlocked("Disambiguation", false, ctxA, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> eventsA = (List<Map<String, Object>>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(eventsA);
            assertEquals(1, eventsA.size());
            assertFalse(eventsA.get(0).containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_SNAPSHOT));

            // Case B: breakerOpen=true
            TraceStore.clear();
            TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, until);
            TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY, since);

            GuardContext ctxB = new GuardContext();
            ctxB.setAuxDegraded(true);
            AuxBlockTracker.markStageBlocked("Disambiguation", true, ctxB, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> eventsB = (List<Map<String, Object>>) TraceStore.get(AuxBlockTracker.ANY_BLOCKED_EVENTS_KEY);
            assertNotNull(eventsB);
            assertEquals(1, eventsB.size());
            assertTrue(eventsB.get(0).containsKey(AuxBlockTracker.EVENT_ALL_OPEN_BREAKERS_SNAPSHOT));
        } finally {
            restore(AllOpenBreakersKeysPolicy.PROP_ALL_OPEN_BREAKERS_KEYS_POLICY, prevKeysPol);
            restore(AllOpenBreakersSnapshotPolicy.PROP_ALL_OPEN_BREAKERS_SNAPSHOT_POLICY, prevSnapPol);
            TraceStore.clear();
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }
}
