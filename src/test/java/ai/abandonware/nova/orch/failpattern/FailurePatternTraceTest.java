package ai.abandonware.nova.orch.failpattern;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void numericSuppressionUsesStableInvalidNumberLabel() {
        FailurePatternTrace.traceSkipped("failurePattern.positiveLong", new NumberFormatException("private ts"));

        assertEquals(Boolean.TRUE, TraceStore.get("failpattern.suppressed.failurePattern.positiveLong"));
        assertEquals("invalid_number",
                TraceStore.get("failpattern.suppressed.failurePattern.positiveLong.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private ts"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }

    @Test
    void nonNumericSuppressionKeepsSanitizedExceptionClass() {
        FailurePatternTrace.traceSkipped("failurePattern.parseJsonl", new IllegalArgumentException("private json"));

        assertEquals(Boolean.TRUE, TraceStore.get("failpattern.suppressed.failurePattern.parseJsonl"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("failpattern.suppressed.failurePattern.parseJsonl.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private json"));
    }

    @Test
    void suppressionTraceIncludesSafeAggregateStageAndErrorType() {
        String rawStage = "failurePattern.parseJsonl " + com.example.lms.test.SecretFixtures.openAiKey();

        FailurePatternTrace.traceSkipped(rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("failpattern.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("failpattern.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("failpattern.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("failpattern.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }
}
