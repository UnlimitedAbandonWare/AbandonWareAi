package com.example.lms.boot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootAutofixRedactionContractTest {

    @Test
    void bootSchemaAutofixLogsDoNotWriteRawThrowableText() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/boot/ChatMessageContentColumnAutoFix.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
            assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"),
                    source + " should log throwable hash/length only");
            assertTrue(code.contains("errorHash={} errorLength={}"),
                    source + " should retain redacted failure diagnostics");
            assertTrue(code.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"),
                    source + " should hash only the throwable message");
        }
    }
}
