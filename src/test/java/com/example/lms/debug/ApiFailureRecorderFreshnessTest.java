package com.example.lms.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiFailureRecorderFreshnessTest {
    @TempDir Path dir;

    @Test void ttlDoesNotManufactureRecoveryOrClearFailureHistory() {
        var clock = new MutableClock();
        var recorder = new ApiFailureRecorder(null, dir.resolve("failure.json"), clock);
        try {
            recorder.record("naver", "webkr", 401, null, null);
            recorder.record("naver", "webkr", 401, null, null);
            clock.advance(600);
            var row = provider(recorder);
            assertEquals("STALE", row.get("state"));
            assertEquals("STALE", recorder.statusSummary().get("overall"));
            assertEquals(2L, row.get("consecutive"));
            assertFalse(row.containsKey("recoveredAt"));
            assertEquals(2L, recorder.snapshot().get(0).count());
        } finally { recorder.close(); }
    }

    @Test void freshSuccessUsesItsOwnTimeThenBecomesUnconfirmedWhenAged() {
        var clock = new MutableClock();
        var recorder = new ApiFailureRecorder(null, dir.resolve("recovery.json"), clock);
        try {
            recorder.record("naver", "webkr", 401, null, null);
            clock.advance(601);
            recorder.recordSuccess("naver", "webkr");
            assertEquals("OK", provider(recorder).get("state"));
            assertEquals(clock.instant().toString(), provider(recorder).get("recoveredAt"));
            clock.advance(600);
            assertEquals("STALE", provider(recorder).get("state"));
            assertEquals(0L, provider(recorder).get("consecutive"));
            assertEquals(1L, recorder.snapshot().get(0).count());
        } finally { recorder.close(); }
    }

    @Test void reloadedOldFailureRetainsHistoryWithoutClaimingCurrentHealth() {
        var clock = new MutableClock();
        var path = dir.resolve("reload.json");
        var original = new ApiFailureRecorder(null, path, clock);
        original.record("naver", "webkr", 403, null, null);
        original.close();
        clock.advance(601);
        var reloaded = new ApiFailureRecorder(null, path, clock);
        try {
            assertTrue(reloaded.persistenceHealthy());
            assertEquals("STALE", provider(reloaded).get("state"));
            assertEquals(1L, provider(reloaded).get("consecutive"));
            assertNull(reloaded.snapshot().get(0).recoveredAt());
        } finally { reloaded.close(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provider(ApiFailureRecorder recorder) {
        return ((List<Map<String, Object>>) recorder.statusSummary().get("providers")).get(0);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
