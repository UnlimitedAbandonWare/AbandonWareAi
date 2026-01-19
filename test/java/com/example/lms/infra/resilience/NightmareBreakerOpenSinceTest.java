package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NightmareBreakerOpenSinceTest {

    @Test
    void openSince_isPropagatedToTraceOnIsOpen_evenAcrossRequests() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setEnabled(true);
        props.setFailureThreshold(1);
        props.setConsecutiveFailureThreshold(1);
        props.setOpenDuration(Duration.ofSeconds(60));

        NightmareBreaker breaker = new NightmareBreaker(props);
        String breakerKey = "test.breaker.openSince";

        // request #1: trigger OPEN
        TraceStore.clear();
        breaker.recordFailure(breakerKey,
                NightmareBreaker.FailureKind.EXCEPTION,
                new RuntimeException("boom"),
                "unit-test");

        NightmareBreaker.StateView v = breaker.inspect(breakerKey);
        assertTrue(v.open, "breaker should be OPEN after failureThreshold=1");
        assertTrue(v.openSinceMs > 0, "openSinceMs should be populated in global breaker state");

        // request #2: a fresh TraceStore should still get the global openSince stamped
        TraceStore.clear();
        assertTrue(breaker.isOpen(breakerKey));

        @SuppressWarnings("unchecked")
        Map<String, Long> openAtMap = (Map<String, Long>) TraceStore.get("nightmare.breaker.openAtMs");
        assertNotNull(openAtMap);
        assertEquals(v.openSinceMs, openAtMap.get(breakerKey));

        @SuppressWarnings("unchecked")
        Map<String, Long> openUntilMap = (Map<String, Long>) TraceStore.get(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY);
        assertNotNull(openUntilMap);
        assertEquals(v.openUntilMs, openUntilMap.get(breakerKey));

        Object openUntilLast = TraceStore.get(NightmareBreaker.TRACE_OPEN_UNTIL_MS_LAST_KEY);
        assertTrue(openUntilLast instanceof Number);
        assertEquals(v.openUntilMs, ((Number) openUntilLast).longValue());
    }
}
