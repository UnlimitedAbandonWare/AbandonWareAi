package com.example.lms.trace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeRedactorFallbackContractTest {

    @Test
    void fallbackDiagnosticsUseLengthsAndTypesOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/SafeRedactor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("SafeRedactor hash fallback textLength={0} errorType={1}"));
        assertTrue(source.contains("SafeRedactor host extraction failed valueLength={0} errorType={1}"));
        assertFalse(source.contains("LOG.log(System.Logger.Level.DEBUG, t"));
        assertFalse(source.contains("LOG.log(System.Logger.Level.DEBUG, value"));
    }
}
