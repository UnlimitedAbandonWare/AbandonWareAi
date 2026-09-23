package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmErrorClassifierRedactionTest {

    @Test
    void quotaIsNonRetryableWithoutRetainingProviderMessageOrCredentials() {
        String sentinel = com.example.lms.test.SecretFixtures.openAiKey();
        String body = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"free_limit_reached: "
                + sentinel + " synthetic-private-detail\"}}";
        LlmErrorClassifier.Result result = LlmErrorClassifier.classify(new RuntimeException("outer",
                new dev.langchain4j.exception.HttpException(429, body)));
        assertFalse(result.retryable());
        assertTrue("NON_REPLAYABLE".equals(result.code()));
        assertFalse(result.shortMessage().contains(sentinel));
        assertFalse(result.shortMessage().contains("synthetic-private-detail"));
    }

    @Test
    void shortMessageMasksSecretsBeforeReturningTraceableText() {
        String rawKey = "" + com.example.lms.test.SecretFixtures.openAiKey() + "";
        RuntimeException ex = new RuntimeException("auth header Bearer " + rawKey + " failed upstream");

        LlmErrorClassifier.Result result = LlmErrorClassifier.classify(ex);

        assertNotNull(result.shortMessage());
        assertFalse(result.shortMessage().contains(rawKey));
        assertFalse(result.shortMessage().contains("Bearer " + rawKey));
    }

    @Test
    void shortMessageSummarizesPromptAndQueryFragments() {
        String rawQuery = "private user query about medical record 123";
        RuntimeException ex = new RuntimeException("upstream rejected prompt=\"" + rawQuery + "\"");

        LlmErrorClassifier.Result result = LlmErrorClassifier.classify(ex);

        assertNotNull(result.shortMessage());
        assertFalse(result.shortMessage().contains(rawQuery));
        assertFalse(result.shortMessage().contains("upstream rejected prompt"));
        assertTrue(result.shortMessage().contains("hash12"));
    }

    @Test
    void blankResponseIsClassifiedAsRetryableBlankResponse() {
        RuntimeException ex = new RuntimeException("LLM blank response");

        LlmErrorClassifier.Result result = LlmErrorClassifier.classify(ex);

        assertTrue(result.retryable());
        assertTrue("BLANK_RESPONSE".equals(result.code()));
    }

    @Test
    void defensiveClassifierCatchLeavesScannerVisibleBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/LlmErrorClassifier.java"));

        assertTrue(source.contains("TraceStore.put(\"llm.errorClassifier.suppressed.classify\", true)"));
        assertTrue(source.contains("TraceStore.put(\"llm.errorClassifier.suppressed.stage\", \"classify\")"));
        assertTrue(source.contains("TraceStore.put(\"llm.errorClassifier.suppressed.errorType\", safeErrorType)"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), \"unknown\")"));
    }
}
