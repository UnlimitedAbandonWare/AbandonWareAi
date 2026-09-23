package com.example.lms.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReactorMdcLifterRedactionContractTest {

    @Test
    void hookInstallFailureLogDoesNotWriteRawThrowableObject() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/web/ReactorMdcLifter.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"Failed to install Reactor MDC lifter\", e);"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("Failed to install Reactor MDC lifter type={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }
}
