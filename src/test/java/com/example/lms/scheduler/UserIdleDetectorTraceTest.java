package com.example.lms.scheduler;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserIdleDetectorTraceTest {

    @Test
    void idleWindowParseFallbackLeavesAggregateBreadcrumbWithoutRawValue() {
        TraceStore.clear();
        try {
            String raw = "ownerToken=raw-secret";
            LocalTime fallback = LocalTime.NOON;

            LocalTime parsed = UserIdleDetector.parseHHmm(raw, fallback);

            assertEquals(fallback, parsed);
            assertEquals("idleWindow.parse", TraceStore.get("user.idle.suppressed.stage"));
            assertEquals("DateTimeParseException", TraceStore.get("user.idle.suppressed.errorType"));
            assertEquals(Boolean.TRUE, TraceStore.get("user.idle.suppressed.idleWindow.parse"));
            assertEquals("DateTimeParseException", TraceStore.get("user.idle.suppressed.idleWindow.parse.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(raw), trace);
            assertFalse(trace.contains("ownerToken"), trace);
        } finally {
            TraceStore.clear();
        }
    }
}
