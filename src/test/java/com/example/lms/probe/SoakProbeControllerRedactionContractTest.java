package com.example.lms.probe;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakProbeControllerRedactionContractTest {

    @Test
    void blockedProbeStepDoesNotCopyOpenCircuitMessage() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/SoakProbeController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\"blocked: \" + oce.getMessage()"));
        assertTrue(source.contains("blockedProbeNote(oce.getMessage(), oce.remaining())"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(message, \"open\")"));
        assertTrue(source.contains("[AWX][probe][soak] sleep interrupted stage=open_wait errorType={}"));
        assertTrue(source.contains("[AWX][probe][soak] breaker still open stage=half_open_check errorType={}"));
        assertTrue(source.contains("[AWX][probe][soak] trial blocked stage=half_open_trial errorType={}"));
    }

    @Test
    void blockedProbeNoteHashesRawBreakerMessageButKeepsRemaining() throws Exception {
        Method method = SoakProbeController.class.getDeclaredMethod(
                "blockedProbeNote", String.class, Duration.class);
        method.setAccessible(true);

        String raw = "NightmareBreaker is OPEN: key=C:\\Users\\nninn\\Desktop\\secret"
                + "\\ownerToken=" + com.example.lms.test.SecretFixtures.openAiKey() + ", remaining=PT1S";
        String note = String.valueOf(method.invoke(null, raw, Duration.ofMillis(1234)));

        assertTrue(note.startsWith("blocked: error="));
        assertTrue(note.contains("remainingMs=1234"));
        assertTrue(note.contains("hash:"));
        assertFalse(note.contains("C:\\Users"));
        assertFalse(note.contains("ownerToken"));
        assertFalse(note.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(note.contains(raw));
    }
}
