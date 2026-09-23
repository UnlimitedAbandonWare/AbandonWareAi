package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingFallbackConfigRedactionContractTest {

    @Test
    void fallbackDiagnosticsDoNotWriteRawModelOrBaseUrl() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/EmbeddingFallbackConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("model={}, dimensions={}, baseUrl={}"));
        assertFalse(source.contains("\"model\", m"));
        assertFalse(source.contains("\"baseUrl\", redactUrl(bu)"));
        assertTrue(source.contains("modelHash={}"));
        assertTrue(source.contains("baseUrlHost={}"));
        assertTrue(source.contains("\"modelHash\", SafeRedactor.hashValue(m)"));
        assertTrue(source.contains("\"baseUrlHash\", SafeRedactor.hashValue(bu)"));
        assertTrue(source.contains("TraceStore.put(\"embeddingFallback.safeHost.suppressed\", true);"));
        assertTrue(source.contains("TraceStore.put(\"embeddingFallback.safeHost.suppressed.errorType\", \"invalid_url\");"));
    }
}
