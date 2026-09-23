package com.example.lms.health;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmHealthRedactionContractTest {

    @Test
    void startupPingSkippedLogDoesNotWriteRawBaseUrlOrModel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/LlmHealth.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("provider={}, baseUrl={}, model={}"));
        assertFalse(source.contains("provider, baseUrl, model"));
        assertFalse(source.contains("local engine='{}'"));
        assertTrue(source.contains("provider={} baseUrlHost={} baseUrlHash={} modelHash={} modelLength={}"));
        assertTrue(source.contains("local engineHash={} engineLength={}"));
        assertTrue(source.contains("safeHost(baseUrl)"));
        assertTrue(source.contains("SafeRedactor.hashValue(baseUrl)"));
        assertTrue(source.contains("SafeRedactor.hashValue(engine)"));
        assertTrue(source.contains("SafeRedactor.hashValue(model)"));
    }

    @Test
    void safeHostParseFailureLeavesFixedStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/LlmHealth.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"llmHealth.safeHost\", ignored);"));
        assertTrue(source.contains("TraceStore.put(\"llm.health.suppressed.\" + safeStage, true);"));
    }

    @Test
    void suppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        TraceStore.clear();
        String rawStage = "llmHealth.safeHost " + com.example.lms.test.SecretFixtures.openAiKey();
        Method method = LlmHealth.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, rawStage, new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("llm.health.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.health.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("llm.health.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("llm.health.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        TraceStore.clear();
    }

    @Test
    void placeholderApiKeyCountsAsMissingWhenFailOnMissingIsEnabled() {
        LlmHealth health = new LlmHealth();
        ReflectionTestUtils.setField(health, "enabled", true);
        ReflectionTestUtils.setField(health, "failOnMissing", true);
        ReflectionTestUtils.setField(health, "engine", "");
        ReflectionTestUtils.setField(health, "provider", "openai");
        ReflectionTestUtils.setField(health, "baseUrl", "https://api.openai.com/v1");
        ReflectionTestUtils.setField(health, "apiKey", "sk-local");
        ReflectionTestUtils.setField(health, "model", "gpt-4o-mini");

        assertThrows(IllegalStateException.class, () -> health.run(null));
    }

    @Test
    void localOllamaSentinelIsAcceptedAsLocalCompatKey() {
        LlmHealth health = new LlmHealth();
        ReflectionTestUtils.setField(health, "enabled", true);
        ReflectionTestUtils.setField(health, "failOnMissing", true);
        ReflectionTestUtils.setField(health, "engine", "");
        ReflectionTestUtils.setField(health, "provider", "local");
        ReflectionTestUtils.setField(health, "baseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(health, "apiKey", "ollama");
        ReflectionTestUtils.setField(health, "model", "gemma3:4b");

        assertDoesNotThrow(() -> health.run(null));
    }

    @Test
    void invalidBaseUrlLeavesStableReasonCodeWithoutLeakingUrlParserClass() {
        TraceStore.clear();
        LlmHealth health = new LlmHealth();
        ReflectionTestUtils.setField(health, "enabled", true);
        ReflectionTestUtils.setField(health, "failOnMissing", false);
        ReflectionTestUtils.setField(health, "engine", "");
        ReflectionTestUtils.setField(health, "provider", "local");
        ReflectionTestUtils.setField(health, "baseUrl", "http://[broken");
        ReflectionTestUtils.setField(health, "apiKey", "ollama");
        ReflectionTestUtils.setField(health, "model", "gemma3:4b");

        assertDoesNotThrow(() -> health.run(null));

        assertEquals(Boolean.TRUE, TraceStore.get("llm.health.suppressed.llmHealth.safeHost"));
        assertEquals("invalid_url", TraceStore.get("llm.health.suppressed.llmHealth.safeHost.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("IllegalArgumentException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("http://[broken"));
        TraceStore.clear();
    }
}
