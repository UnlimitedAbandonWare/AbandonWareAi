package com.example.lms.transform;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTransformerRedactionContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void queryTransformerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "QueryTransformer must keep fail-soft breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.minLiveBudget\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.minLiveBudgetTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.softCooldownRemaining\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.cooldownStreak\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.cooldownJitter\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.cooldownUntil\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.cooldownTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.degradedDebug\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.forceKillSchedule\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.hintTimeoutDebug\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.interruptedDebug\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.execExceptionDebug\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.exception\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.exceptionDebug\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.costZone\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.orchEnabledRead\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.breakerRead\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.softCooldownActiveTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.failurePatternCooldown\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.stagePolicyTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.noiseGate\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.ctxReason\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.noiseEscapeTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.noiseOverrideTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.suppressed.bypassTrace\", true)"));
        assertTrue(source.contains("traceSuppressed(\"breakerOpen.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"promptTooLong.degradedTrace\")"));
        assertTrue(source.contains("traceSuppressed(\"promptTooLong.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"blank.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"friendshield.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"cooldownStreakReset\")"));
        assertTrue(source.contains("traceSuppressed(\"config.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"config.trace\")"));
        assertTrue(source.contains("traceSuppressed(\"canceled.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"runLlmFailure.debug\")"));
        assertTrue(source.contains("traceSuppressed(\"normalized.recoverContext\")"));
        assertTrue(source.contains("traceSuppressed(\"normalized.blankTrace\")"));
        assertTrue(source.contains("traceSuppressed(\"normalized.recoveredTrace\")"));
        assertTrue(source.contains("traceSuppressed(\"normalized.orchTrace\")"));
        assertTrue(source.contains("traceSuppressed(\"correction.fallback\", e)"));
        assertTrue(source.contains("traceSuppressed(\"variants.fallback\", e)"));
        assertTrue(source.contains("traceSuppressed(\"userPrompt.recoverContext\")"));
        assertTrue(source.contains("traceSuppressed(\"userPrompt.recoveredOrchTrace\")"));
        assertTrue(source.contains("traceSuppressed(\"classifyIntent.fallback\", e)"));
    }

    @Test
    void modelRequiredDiagnosticsDoNotWriteRawBaseUrlModelOrCurlHint() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"qtx.llm.fastBaseUrl\", baseUrl)"));
        assertFalse(source.contains("TraceStore.put(\"qtx.llm.fastModel\", model)"));
        assertFalse(source.contains("TraceStore.put(\"qtx.llm.curlHint\", curlHint)"));
        assertFalse(source.contains("fastBaseUrl={} fastModel={} curlHint={}"));
        assertFalse(source.contains("dd.put(\"fastBaseUrl\", baseUrl)"));
        assertFalse(source.contains("dd.put(\"fastModel\", model)"));
        assertFalse(source.contains("dd.put(\"curlHint\", curlHint)"));
        assertFalse(source.contains("TraceStore.put(\"qtx.softCooldown.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"queryTransformer.reason\", reasonCode);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"aux.queryTransformer.degraded.reason\", reasonCode);"));
        assertFalse(source.contains("dd.put(\"reason\", reasonCode);"));
        assertFalse(source.contains("dd.put(\"reason\", reason);"));
        assertFalse(source.contains("dd.put(\"message\", m.length()"));
        assertFalse(source.contains("dd.put(\"message\", m);"));

        assertTrue(source.contains("TraceStore.put(\"qtx.llm.fastBaseUrlHash\", SafeRedactor.hashValue(baseUrl))"));
        assertTrue(source.contains("TraceStore.put(\"qtx.llm.fastModelHash\", SafeRedactor.hashValue(model))"));
        assertTrue(source.contains("TraceStore.put(\"qtx.llm.curlHintHash\", SafeRedactor.hashValue(curlHint))"));
        assertFalse(source.contains("TraceStore.put(\"qtx.softCooldown.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains("String safeReasonCode = SafeRedactor.safeMessage(reasonCode, 120);"));
        assertTrue(source.contains("TraceStore.put(\"qtx.softCooldown.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains("String safeReasonCode = SafeRedactor.traceLabelOrFallback(reasonCode, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"queryTransformer.reason\", safeReasonCode);"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"aux.queryTransformer.degraded.reason\", safeReasonCode);"));
        assertTrue(source.contains("dd.put(\"reason\", safeReasonCode);"));
        assertFalse(source.contains("dd.put(\"reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("dd.put(\"reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains("fastBaseUrlHash={} fastBaseUrlLength={} fastModelHash={} fastModelLength={} curlHintHash={} curlHintLength={}"));
        assertTrue(source.contains("dd.put(\"fastBaseUrlHash\", SafeRedactor.hashValue(baseUrl))"));
        assertTrue(source.contains("dd.put(\"curlHintLength\", curlHint == null ? 0 : curlHint.length())"));
        assertTrue(source.contains("dd.put(\"messageHash\", SafeRedactor.hashValue(m));"));
        assertTrue(source.contains("dd.put(\"messageLength\", m.length());"));
    }

    @Test
    void faultMaskContextUsesPromptSha1InsteadOfRawPrompt() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/transform/QueryTransformer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "faultMaskingLayerMonitor.record(\"query-transformer:runLLM\", e, prompt, \"caught-and-fallback\")"));
        assertTrue(source.contains(
                "\"promptSha1=\" + com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt),"));
    }

    @Test
    void failureSupportHelpersLiveOutsideQueryTransformerLargeFile() throws Exception {
        Path transformerPath = Path.of("main/java/com/example/lms/transform/QueryTransformer.java");
        Path helperPath = Path.of("main/java/com/example/lms/transform/QueryTransformerFailureSupport.java");

        String source = Files.readString(transformerPath, StandardCharsets.UTF_8);

        assertTrue(Files.exists(helperPath), "LLM failure support helpers should live outside QueryTransformer");
        String helper = Files.readString(helperPath, StandardCharsets.UTF_8);
        assertTrue(source.contains("import static com.example.lms.transform.QueryTransformerFailureSupport.*;"));
        assertFalse(source.contains("private static NightmareBreaker.FailureKind classifyLlmFailure("));
        assertFalse(source.contains("private static Throwable unwrap("));
        assertFalse(source.contains("private static String buildCurlHint("));
        assertTrue(helper.contains("final class QueryTransformerFailureSupport"));
        assertTrue(helper.contains("static NightmareBreaker.FailureKind classifyLlmFailure("));
        assertTrue(helper.contains("traceSuppressed(\"nightmareClassify\", ignore)"));
        assertTrue(helper.contains("traceSuppressed(\"messageRead\", ignore)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.failureSupport.suppressed.stage\", safeStage)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.failureSupport.suppressed.errorType\", safeErrorType)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.failureSupport.suppressed.\" + safeStage, true)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.failureSupport.suppressed.\" + safeStage + \".errorType\", safeErrorType)"));
        assertFalse(helper.contains("failure.getMessage()"));
    }

    @Test
    void failureSupportSuppressedTraceIncludesSafeAggregateStageAndErrorType() {
        boolean modelLoading = QueryTransformerFailureSupport.looksLikeModelLoading(
                new ThrowingMessageException("ownerToken=query-transformer-secret"));

        assertFalse(modelLoading);
        assertEquals("messageRead", TraceStore.get("qtx.failureSupport.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("qtx.failureSupport.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.failureSupport.suppressed.messageRead"));
        assertEquals("IllegalStateException", TraceStore.get("qtx.failureSupport.suppressed.messageRead.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=query-transformer-secret"));
    }

    private static final class ThrowingMessageException extends RuntimeException {
        private final String raw;

        private ThrowingMessageException(String raw) {
            this.raw = raw;
        }

        @Override
        public String getMessage() {
            throw new IllegalStateException(raw);
        }
    }
}
