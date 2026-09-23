package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class VectorPoisonGuardRedactionContractTest {

    @Test
    void vectorPoisonGuardDoesNotRecordRawSidValues() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/VectorPoisonGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("sid=%s)"));
        assertFalse(source.contains("sid={}\","));
        assertFalse(source.contains("stackLines, sid == null ? \"\" : sid"));
        assertFalse(source.contains("(reason == null ? \"\" : reason)"));
        assertFalse(source.contains("stage, reason, lines, logLines, stackLines"));
        assertFalse(source.contains("SafeRedactor.safeMessage(reason, 120)"));
        assertTrue(source.contains("sidHash"));
        assertTrue(source.contains("SafeRedactor.hashValue(sid)"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(reason, \"\")"));
    }

    @Test
    void vectorPoisonGuardDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/VectorPoisonGuard.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "VectorPoisonGuard fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void vectorPoisonTraceSuppressionsNormalizeNumericErrorType() throws Exception {
        TraceStore.clear();
        Method traceSuppressed = VectorPoisonGuard.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        traceSuppressed.setAccessible(true);

        traceSuppressed.invoke(null, "record.parse", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.poison.suppressed.record.parse"));
        assertEquals("invalid_number", TraceStore.get("vector.poison.suppressed.record.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }
}
