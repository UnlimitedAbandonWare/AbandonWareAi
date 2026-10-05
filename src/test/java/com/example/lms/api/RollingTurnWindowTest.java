package com.example.lms.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RollingTurnWindowTest {
    private List<RollingTurnWindow.Rule> rules(String owner, String ip, boolean hourly) {
        var rules = new java.util.ArrayList<RollingTurnWindow.Rule>();
        rules.add(new RollingTurnWindow.Rule(owner, 10, 60_000, 600, "minute"));
        if (hourly) rules.add(new RollingTurnWindow.Rule(owner, 10, 3_600_000, 600, "hour"));
        rules.add(new RollingTurnWindow.Rule(ip, 60, 60_000, 60, "minute"));
        return rules;
    }
    @Test void retryWaitsForLatestBlockingWindowAndExactHourBoundary() {
        var clock = new AtomicLong(); var window = new RollingTurnWindow(8192, clock::get);
        for (int i = 0; i < 10; i++) assertTrue(window.tryAcquire(null, rules("a", "ip", true)).accepted());
        clock.set(59_999); var denied = window.tryAcquire(null, rules("a", "ip", true));
        assertFalse(denied.accepted()); assertEquals("hour", denied.scope()); assertEquals(3_540_001, denied.retryAfterMs());
        clock.set(3_599_999); assertFalse(window.tryAcquire(null, rules("a", "ip", true)).accepted());
        clock.set(3_600_000); assertTrue(window.tryAcquire(null, rules("a", "ip", true)).accepted());
    }
    @Test void ownerRejectionDoesNotChargeIpAndIpRejectionDoesNotChargeOwner() {
        var clock = new AtomicLong(); var window = new RollingTurnWindow(8192, clock::get);
        for (int i = 0; i < 10; i++) window.tryAcquire(null, rules("full-owner", "ip-a", false));
        for (int i = 0; i < 100; i++) assertFalse(window.tryAcquire(null, rules("full-owner", "ip-b", false)).accepted());
        for (int i = 0; i < 60; i++) assertTrue(window.tryAcquire(null, rules("fresh-" + i, "ip-b", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("target", "ip-b", false)).accepted());
        for (int i = 0; i < 10; i++) assertTrue(window.tryAcquire(null, rules("target", "ip-c", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("target", "ip-c", false)).accepted());
    }
    @Test void equalTimestampReceiptsReleaseExactlyOnce() {
        var window = new RollingTurnWindow(8192, () -> 0);
        var first = window.tryAcquire(null, rules("a", "ip", false));
        for (int i = 0; i < 9; i++) window.tryAcquire(null, rules("a", "ip", false));
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
        window.release(first); window.release(first);
        assertTrue(window.tryAcquire(null, rules("a", "ip", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
    }
    @Test void duplicateReceiptCannotRefundOriginalAndMarkedOriginalRemovesReplay() {
        var window = new RollingTurnWindow(8192, () -> 0);
        var original = window.tryAcquire("key", rules("a", "ip", false));
        var duplicate = window.tryAcquire("key", rules("a", "ip", false)); window.release(duplicate);
        for (int i = 0; i < 9; i++) window.tryAcquire(null, rules("a", "ip", false));
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
        window.release(original);
        assertTrue(window.tryAcquire("key", rules("a", "ip", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
    }
    @Test void boundedMapsExposeEvictionAndLeastRecentlyUsedKeyIsEvicted() {
        var window = new RollingTurnWindow(3, () -> 0);
        window.tryAcquire("one", List.of(new RollingTurnWindow.Rule("a", 1, 60_000, 1, "minute")));
        window.tryAcquire("two", List.of(new RollingTurnWindow.Rule("b", 1, 60_000, 1, "minute")));
        window.tryAcquire("three", List.of(new RollingTurnWindow.Rule("c", 1, 60_000, 1, "minute")));
        assertFalse(window.tryAcquire(null, List.of(new RollingTurnWindow.Rule("a", 1, 60_000, 1, "minute"))).accepted());
        window.tryAcquire("four", List.of(new RollingTurnWindow.Rule("d", 1, 60_000, 1, "minute")));
        assertEquals(3, window.keyCount()); assertEquals(3, window.replayKeyCount());
        assertEquals(1, window.evictedKeys()); assertEquals(1, window.evictedReplayKeys());
        assertFalse(window.tryAcquire(null, List.of(new RollingTurnWindow.Rule("a", 1, 60_000, 1, "minute"))).accepted());
        assertTrue(window.tryAcquire(null, List.of(new RollingTurnWindow.Rule("b", 1, 60_000, 1, "minute"))).accepted());
    }
    @Test void clockRegressionDoesNotExpireQuotaOrReplay() {
        var clock = new AtomicLong(100_000); var window = new RollingTurnWindow(3, clock::get);
        var single = List.of(new RollingTurnWindow.Rule("a", 1, 60_000, 1, "minute"));
        assertTrue(window.tryAcquire("key", single).accepted()); clock.set(0);
        assertFalse(window.tryAcquire(null, single).accepted()); assertTrue(window.tryAcquire("key", single).accepted());
        clock.set(160_000); assertTrue(window.tryAcquire(null, single).accepted());
    }
    @Test void invalidCapacityOrRuleCannotFailOpen() {
        assertThrows(IllegalArgumentException.class, () -> new RollingTurnWindow(0, () -> 0));
        var window = new RollingTurnWindow(3, () -> 0);
        assertThrows(IllegalArgumentException.class, () -> window.tryAcquire(null,
                List.of(new RollingTurnWindow.Rule("a", 0, 60_000, 10, "minute"))));
        assertEquals(0, window.keyCount());
    }
    @Test void hourlyOffHistorySurvivesOutOfOrderRefund() {
        var clock = new AtomicLong(); var window = new RollingTurnWindow(8192, clock::get);
        var receipts = new java.util.ArrayList<RollingTurnWindow.Result>();
        for (int i = 0; i < 11; i++) {
            clock.set(i * 60_000L); var receipt = window.tryAcquire("key" + i, rules("a", "ip", false));
            assertTrue(receipt.accepted()); receipts.add(receipt);
        }
        clock.set(600_001); window.release(receipts.get(1));
        var denied = window.tryAcquire(null, rules("a", "ip", true));
        assertFalse(denied.accepted()); assertEquals("hour", denied.scope());
        assertEquals(2_999_999, denied.retryAfterMs());
    }
    @Test void fingerprintFenceRejectsDuplicateAndMismatchWithoutChargingOrRefundingOriginal() {
        var window = new RollingTurnWindow(8192, () -> 0);
        var original = window.tryAcquire("key", "fingerprint-a", rules("a", "ip", false));
        assertTrue(original.accepted());
        var duplicate = window.tryAcquire("key", "fingerprint-a", rules("a", "ip", false));
        var mismatch = window.tryAcquire("key", "fingerprint-b", rules("a", "ip", false));
        assertFalse(duplicate.accepted()); assertEquals("idempotency_duplicate", duplicate.rejectionReason());
        assertFalse(mismatch.accepted()); assertEquals("idempotency_payload_mismatch", mismatch.rejectionReason());
        window.release(duplicate); window.release(mismatch);
        for (int i = 0; i < 9; i++) assertTrue(window.tryAcquire(null, rules("a", "ip", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
        window.release(original); window.release(original);
        assertTrue(window.tryAcquire("key", "fingerprint-b", rules("a", "ip", false)).accepted());
        assertFalse(window.tryAcquire(null, rules("a", "ip", false)).accepted());
    }
    @Test void fingerprintFenceExpiresAtExactHourAndDoesNotExtendOnRejectedReplay() {
        var clock = new AtomicLong(); var window = new RollingTurnWindow(8192, clock::get);
        assertTrue(window.tryAcquire("key", "fingerprint-a", rules("a", "ip", false)).accepted());
        clock.set(3_599_999);
        assertEquals("idempotency_duplicate",
                window.tryAcquire("key", "fingerprint-a", rules("a", "ip", false)).rejectionReason());
        clock.set(3_600_000);
        assertTrue(window.tryAcquire("key", "fingerprint-b", rules("a", "ip", false)).accepted());
    }
}
