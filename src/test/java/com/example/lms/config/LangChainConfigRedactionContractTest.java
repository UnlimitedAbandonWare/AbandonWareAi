package com.example.lms.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class LangChainConfigRedactionContractTest {

    @Test
    void langChainConfigDoesNotUseExactEmptyCatchBlocks() throws Exception {
        Path source = Path.of("main/java/com/example/lms/config/LangChainConfig.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(code)
                        .find(),
                source + " should log or trace fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void vectorStoreFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        Path source = Path.of("main/java/com/example/lms/config/LangChainConfig.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = code.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
    }

    @Test
    void openAiBuilderSinksUsePlaceholderAwareKeyGuard() throws Exception {
        Path source = Path.of("main/java/com/example/lms/config/LangChainConfig.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        String activeCode = code.lines()
                .map(String::trim)
                .filter(line -> !line.startsWith("*"))
                .filter(line -> !line.startsWith("//"))
                .collect(Collectors.joining("\n"));

        assertTrue(code.contains("usableOpenAiKey()"));
        assertTrue(code.contains("ConfigValueGuards.isMissing(openAiKey)"));
        assertFalse(activeCode.contains(".apiKey(openAiKey)"));
        assertFalse(activeCode.contains("openAiKey == null || openAiKey.isBlank()"));
    }

    @Test
    void vectorStoreDegradedLogsUseHashAndLengthOnly() throws Exception {
        Path source = Path.of("main/java/com/example/lms/config/LangChainConfig.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(code.contains("[VectorStore][STRICT] vector upsert failed: {}"));
        assertFalse(code.contains("vector upsert degraded: {}"));
        assertFalse(code.contains("[VectorStore][STRICT] vector upsert failed (stable-ids): {}"));
        assertFalse(code.contains("vector upsert degraded (stable-ids): {}"));
        assertFalse(code.contains("vector query degraded: {}"));
        assertTrue(code.contains("[VectorStore][STRICT] vector upsert failed. errorHash={} errorLength={}"));
        assertTrue(code.contains("vector upsert degraded. errorHash={} errorLength={}"));
        assertTrue(code.contains("[VectorStore][STRICT] vector upsert failed (stable-ids). errorHash={} errorLength={}"));
        assertTrue(code.contains("vector upsert degraded (stable-ids). errorHash={} errorLength={}"));
        assertTrue(code.contains("vector query degraded. errorHash={} errorLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(code.contains("traceSuppressed(\"vector.upsert\");"));
        assertTrue(code.contains("traceSuppressed(\"vector.upsertStableIds\");"));
        assertTrue(code.contains("traceSuppressed(\"vector.ingestProtection\");"));
        assertTrue(code.contains("traceSuppressed(\"vector.ingestProtectionStableIds\");"));
        assertTrue(code.contains("traceSuppressed(\"vector.metadataFingerprint\");"));
        assertTrue(code.contains("traceSuppressed(\"vector.legacyFingerprintCheck\");"));
        assertTrue(code.contains("TraceStore.put(\"vector.suppressed.\" + traceStage(stage), true);"));
    }
}
