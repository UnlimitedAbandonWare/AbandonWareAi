package com.example.lms.service.disambiguation;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DisambiguationTraceSuppressionsTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void normalizesNumericErrorType() {
        DisambiguationTraceSuppressions.trace(
                "cooldown.trace",
                new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number",
                TraceStore.get("disambiguation.suppressed.cooldown.trace.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
    }
}
