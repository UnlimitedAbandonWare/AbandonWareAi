package com.example.lms.infra.resilience;

import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidRotationAdvisorTest {

    @Test
    @SuppressWarnings("unchecked")
    void snapshotEvictsAllStateForAnExpiredIdleSid() {
        SidRotationAdvisor advisor = new SidRotationAdvisor();
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "globalOnly", false);
        ReflectionTestUtils.setField(advisor, "windowMs", 5_000L);
        ReflectionTestUtils.setField(advisor, "quarantineThreshold", 1);
        ReflectionTestUtils.setField(advisor, "cooldownMs", 5_000L);
        String sid = "expired-idle-sid";

        advisor.recordQuarantine(sid, "quarantine_guard");

        Map<String, Deque<Long>> quarantineEvents =
                (Map<String, Deque<Long>>) ReflectionTestUtils.getField(advisor, "quarantineEvents");
        Map<String, Long> recommendedAt =
                (Map<String, Long>) ReflectionTestUtils.getField(advisor, "recommendedAt");
        Map<String, String> lastReason =
                (Map<String, String>) ReflectionTestUtils.getField(advisor, "lastReason");
        long expiredAt = System.currentTimeMillis() - 60_000L;
        Deque<Long> timestamps = quarantineEvents.get(sid);
        synchronized (timestamps) {
            timestamps.clear();
            timestamps.addLast(expiredAt);
        }
        recommendedAt.put(sid, expiredAt);

        Map<String, Object> snapshot = advisor.snapshot();
        Map<String, Object> sids = (Map<String, Object>) snapshot.get("sids");

        assertFalse(sids.containsKey(sid));
        assertFalse(quarantineEvents.containsKey(sid));
        assertFalse(recommendedAt.containsKey(sid));
        assertFalse(lastReason.containsKey(sid));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPrunesExpiredStateWhenTheWindowSweepIsDue() {
        SidRotationAdvisor advisor = new SidRotationAdvisor();
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "globalOnly", false);
        ReflectionTestUtils.setField(advisor, "windowMs", 5_000L);
        ReflectionTestUtils.setField(advisor, "quarantineThreshold", 1);
        ReflectionTestUtils.setField(advisor, "cooldownMs", 5_000L);
        String expiredSid = "expired-before-next-record";

        advisor.recordQuarantine(expiredSid, "quarantine_guard");

        Map<String, Deque<Long>> quarantineEvents =
                (Map<String, Deque<Long>>) ReflectionTestUtils.getField(advisor, "quarantineEvents");
        Map<String, Long> recommendedAt =
                (Map<String, Long>) ReflectionTestUtils.getField(advisor, "recommendedAt");
        Map<String, String> lastReason =
                (Map<String, String>) ReflectionTestUtils.getField(advisor, "lastReason");
        AtomicLong nextPruneAt =
                (AtomicLong) ReflectionTestUtils.getField(advisor, "nextPruneAt");
        long expiredAt = System.currentTimeMillis() - 60_000L;
        Deque<Long> timestamps = quarantineEvents.get(expiredSid);
        synchronized (timestamps) {
            timestamps.clear();
            timestamps.addLast(expiredAt);
        }
        recommendedAt.put(expiredSid, expiredAt);
        nextPruneAt.set(0L);

        advisor.recordQuarantine("fresh-sid", "quarantine_guard");

        assertFalse(quarantineEvents.containsKey(expiredSid));
        assertFalse(recommendedAt.containsKey(expiredSid));
        assertFalse(lastReason.containsKey(expiredSid));
        assertTrue(quarantineEvents.containsKey("fresh-sid"));
    }

    @Test
    void snapshotLastReasonDoesNotExposeRawSecrets() {
        SidRotationAdvisor advisor = new SidRotationAdvisor();
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "globalOnly", true);
        ReflectionTestUtils.setField(advisor, "windowMs", 600_000L);
        ReflectionTestUtils.setField(advisor, "poisonThreshold", 1);
        String fakeKey = "sk-" + "sid-rotation-placeholder-1234567890";
        String reason = "poison_guard api_key=" + fakeKey;

        advisor.recordPoison(
                LangChainRAGService.GLOBAL_SID,
                reason);

        Map<String, Object> snapshot = advisor.snapshotFor(LangChainRAGService.GLOBAL_SID);
        String lastReason = String.valueOf(snapshot.get("lastReason"));

        assertTrue(snapshot.containsKey("lastReason"));
        assertEquals(SafeRedactor.traceLabelOrFallback(reason, "unknown"), lastReason);
        assertFalse(lastReason.contains(fakeKey));
        assertFalse(lastReason.contains("api_key=" + fakeKey));
    }
}
