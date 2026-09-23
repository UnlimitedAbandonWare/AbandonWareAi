package com.example.lms.probe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeConfigCredentialContractTest {

    @Test
    void braveKeyPresenceDiagnosticsUsePlaceholderAwareGuard() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/ProbeConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("!env.getProperty(\"gpt-search.brave.subscription-token\").isBlank()"));
        assertFalse(source.contains("!env.getProperty(\"gpt-search.brave.api-key\").isBlank()"));
        assertTrue(source.contains("import com.example.lms.config.ConfigValueGuards;"));
        assertTrue(source.contains("hasUsableConfigValue(env, \"gpt-search.brave.subscription-token\")"));
        assertTrue(source.contains("hasUsableConfigValue(env, \"gpt-search.brave.api-key\")"));
        assertTrue(source.contains("!ConfigValueGuards.isMissing(env.getProperty(key))"));
    }

    @Test
    void probeConfigDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/ProbeConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "Probe config diagnostics need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void probeConfigFallbacksUseScannerVisibleTraceHelper() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/ProbeConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("traceProbeSkipped("));
        assertTrue(source.contains("traceSkipped(\"brave_config\", e);"));
        assertTrue(source.contains("traceSkipped(\"guard_config\", e);"));
        assertTrue(source.contains("traceSkipped(\"selected_trace\", e);"));
        assertTrue(source.contains("traceSkipped(\"console_trace\", e);"));
        assertTrue(source.contains("traceSkipped(\"console_trace_emit\", e);"));
        assertTrue(source.contains("traceSkipped(\"env_present\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"config_value_guard\", ignore);"));
    }
}
