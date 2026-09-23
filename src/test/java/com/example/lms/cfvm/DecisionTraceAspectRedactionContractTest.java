package com.example.lms.cfvm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionTraceAspectRedactionContractTest {

    @Test
    void errorTraceUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/cfvm/DecisionTraceAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log(\"ERR\", pjp, System.currentTimeMillis()-ts, t.toString());"));
        assertFalse(source.contains("t.toString()"));
        assertTrue(source.contains("import com.example.lms.trace.SafeRedactor;"));
        assertTrue(source.contains("import com.example.lms.search.TraceStore;"));
        assertTrue(source.contains("String safeError = SafeRedactor.safeMessage(err, 180);"));
        assertTrue(source.contains("errHash"));
        assertTrue(source.contains("errLength"));
        assertTrue(source.contains("errPresent"));
        assertTrue(source.contains("TraceStore.inc(\"cfvm.decisionTrace.writeFailure.count\");"));
        assertFalse(source.contains("TraceStore.put(\"cfvm.decisionTrace.writeFailure.errorType\", ex.getClass().getSimpleName());"));
        assertTrue(source.contains("TraceStore.put(\"cfvm.decisionTrace.writeFailure.errorType\", \"decision_trace_write_failed\");"));
        assertFalse(source.contains("\"err\":"));
        assertFalse(source.contains("new FileWriter("));
        assertFalse(source.contains("catch (IOException ignored) {}"));
    }
}
