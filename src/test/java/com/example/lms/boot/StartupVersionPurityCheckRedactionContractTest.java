package com.example.lms.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class StartupVersionPurityCheckRedactionContractTest {

    @Test
    void dumpInconsistentArtifactsDoesNotReturnRawExceptionMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/boot/StartupVersionPurityCheck.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("return \"ERROR: \" + e.getMessage();"));
        assertTrue(source.contains("traceSuppressed(\"startupVersion.dumpInconsistentArtifacts\", e);"));
        assertTrue(source.contains(
                "return \"ERROR: \" + com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\");"));
    }
}
