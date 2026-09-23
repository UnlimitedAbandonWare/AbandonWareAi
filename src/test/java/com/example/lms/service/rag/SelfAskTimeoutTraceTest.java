package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskTimeoutTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recordCancellationRequestedSanitizesSensitiveStageLabel() throws Exception {
        String secret = "sk-" + "selfAskTimeoutStageSecret123456789";

        java.lang.reflect.Method method = SelfAskTimeoutTrace.class.getDeclaredMethod(
                "recordCancellationRequested",
                String.class,
                long.class,
                String.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(null, "stage=" + secret, 25L, "query " + secret, false, true);

        Object stage = TraceStore.get("selfask.timeout.stage");
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(String.valueOf(stage).startsWith("hash:"));
        assertEquals(stage, TraceStore.get("selfask.timeout.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelRequested"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelInterrupt"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelAccepted"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.timeout.callerInterrupted"));
        assertFalse(TraceStore.getAll().containsKey("selfask.timeout.cancelSuppressed"));
        assertFalse(trace.contains(secret));
    }
}
