package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebFailSoftTraceSuppressionsTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void numericParseSuppressionUsesStableInvalidNumberLabel() {
        WebFailSoftTraceSuppressions.trace("numeric.parse", new NumberFormatException("private value 12345"));

        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.suppressed.numeric.parse"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.suppressed.numeric.parse.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private value 12345"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }

    @Test
    void nonNumericSuppressionKeepsSanitizedExceptionClass() {
        WebFailSoftTraceSuppressions.trace("state.transition", new IllegalStateException("private state"));

        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.suppressed.state.transition"));
        assertEquals("IllegalStateException", TraceStore.get("web.failsoft.suppressed.state.transition.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private state"));
    }
}
