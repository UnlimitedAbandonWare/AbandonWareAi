package com.example.lms.service;

import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.routing.RouteSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelFetchServiceRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void trimForLogMasksSecretLikeErrorBodies() {
        String fakeKey = "sk-" + "test-model-fetch-secret";
        String raw = "{\"error\":\"failed api_key=" + fakeKey + "\","
                + "\"authorization\":\"Bearer " + "raw-owner-token\"}";

        String rendered = ReflectionTestUtils.invokeMethod(ModelFetchService.class, "trimForLog", raw);

        assertFalse(rendered.contains(fakeKey));
        assertFalse(rendered.contains("raw-owner-token"));
        assertTrue(rendered.contains("hash12"));
    }

    @Test
    void trimForLogSummarizesNonSecretErrorBodies() {
        String raw = "{\"error\":\"model store failed for private path C:/Users/alice/models\"}";

        String rendered = ReflectionTestUtils.invokeMethod(ModelFetchService.class, "trimForLog", raw);

        assertFalse(rendered.contains(raw));
        assertFalse(rendered.contains("private path C:/Users/alice/models"));
        assertTrue(rendered.contains("hash12"));
    }

    @Test
    void modelSyncTreatsPlaceholderApiKeyAsMissing() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelSyncService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("if (apiKey == null || apiKey.isBlank())"));
        assertTrue(source.contains("ConfigValueGuards.isMissing(apiKey)"));
        assertTrue(source.contains("traceSuppressed(\"model.ownerDefault\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"model.sync\", e);"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
    }

    @Test
    void modelSyncMissingKeyLeavesProviderDisabledTraceWithoutRepositoryWrite() {
        ModelEntityRepository repository = mock(ModelEntityRepository.class);
        ModelSyncService service = new ModelSyncService(repository);
        ReflectionTestUtils.setField(service, "apiKey", "sk-local");

        service.fetchAndStoreModels();

        assertEquals(Boolean.TRUE, TraceStore.get("model.sync.providerDisabled"));
        assertEquals("missing_openai_api_key", TraceStore.get("model.sync.disabledReason"));
        verifyNoInteractions(repository);
    }

    @Test
    void modelFetchTreatsPlaceholderApiKeyAsMissing() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelFetchService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("openaiApiKey == null || openaiApiKey.isBlank()"));
        assertTrue(source.contains("ConfigValueGuards.isMissing(openaiApiKey)"));
    }

    @Test
    void modelAndLlmFailSoftLogsDoNotUseRawThrowableMessagesOrLocalPaths() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"),
                Path.of("main/java/com/example/lms/service/ModelFetchService.java"),
                Path.of("main/java/com/example/lms/service/FineTuningService.java"),
                Path.of("main/java/com/example/lms/service/llm/LangChain4jLlmClient.java"),
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()")
                            || line.contains(".toString()")
                            || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }

        String modelFetch = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelFetchService.java"),
                StandardCharsets.UTF_8);
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);
        String fineTuning = Files.readString(
                Path.of("main/java/com/example/lms/service/FineTuningService.java"),
                StandardCharsets.UTF_8);
        assertFalse(modelFetch.contains("models dir '{}': {}"));
        assertFalse(modelFetch.contains("Created directory: {}"));
        assertFalse(fineTuning.contains("trainingFile.tempFile().getAbsolutePath()"));
        assertFalse(fineTuning.contains("validationFile.tempFile().getAbsolutePath()"));
        assertFalse(fineTuning.contains("new FileWrapper(tempFile, tempFile.getAbsolutePath())"));
        assertFalse(fineTuning.contains("writeJsonl("));
        assertFalse(fineTuning.contains("FileWrapper"));
        assertFalse(fineTuning.contains("trainingFile.pathHash()"));
        assertFalse(fineTuning.contains("validationFile.pathHash()"));
        assertFalse(fineTuning.contains("tempFileHash={}"));
        assertTrue(fineTuning.contains("[AWX][fine-tuning] provider-disabled disabledReason={}"));
        assertFalse(router.contains("requested model='{}'"));
        assertTrue(modelFetch.contains("modelsDirHash={}"));
        assertTrue(router.contains("modelHash={}"));
    }

    @Test
    void modelFetchFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelFetchService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(retryEx), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("retry after heal) errorHash={} errorLength={}"));
        assertTrue(source.contains("Local LLM endpoint unreachable errorHash={} errorLength={}"));
        assertTrue(source.contains("Remote LLM endpoint unreachable errorHash={} errorLength={}"));
        assertTrue(source.contains("modelsDirHash={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf("));
        assertTrue(source.contains("traceSuppressed(\"models.httpStatus\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"models.retryAfterHeal\", retryEx);"));
        assertTrue(source.contains("traceSuppressed(\"models.resourceAccess\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"models.update\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"url.isLocalHost\", e);"));
        assertTrue(source.contains("traceSuppressed(\"os.isWindows\", e);"));
        assertTrue(source.contains("traceSuppressed(\"ollama.mkdirHeal\", e);"));
        assertTrue(source.contains("traceSuppressed(\"models.safeBody\", ignore);"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
    }

    @Test
    void langChain4jLlmClientUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/llm/LangChain4jLlmClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("complete(String prompt)"));
        assertFalse(source.contains("completeWithKey(String breakerKey, String prompt)"));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("complete(String llmCompletionPrompt)"));
        assertTrue(source.contains("completeWithKey(String breakerKey, String llmCompletionPrompt)"));
        assertTrue(source.contains("UserMessage.from(llmCompletionPrompt)"));
    }

    @Test
    void langChain4jLlmClientFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/llm/LangChain4jLlmClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("LLM call failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void smartFallbackFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[SmartFallback] ChatModel fallback failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void policyRouterSignalReasonsUseSafeMessages() throws Exception {
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);

        assertFalse(router.contains("TraceStore.put(\"ml.router.reason\", sig.reason());"));
        assertFalse(router.contains("ev.put(\"reason\", sig.reason());"));
        assertFalse(router.contains("dd.put(\"reason\", sig.reason());"));
        assertFalse(router.contains("log.debug(\"[Router] promote -> highModel (reason={})\", sig.reason());"));
        assertFalse(router.contains("String safeSignalReason = SafeRedactor.safeMessage(sig.reason(), 120);"));
        assertTrue(router.contains("String safeSignalReason = SafeRedactor.traceLabelOrFallback(sig.reason(), \"unknown\");"));
        assertTrue(router.contains("TraceStore.put(\"ml.router.reason\", safeSignalReason);"));
        assertTrue(router.contains("ev.put(\"reason\", safeSignalReason);"));
        assertTrue(router.contains("dd.put(\"reason\", safeSignalReason);"));
    }

    @Test
    void policyRouterBuildFailureDebugMessageUsesSafeRedactor() throws Exception {
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);

        assertFalse(router.contains("dd.put(\"message\", String.valueOf(e.getMessage()));"));
        assertFalse(router.contains(
                "dd.put(\"message\", SafeRedactor.safeMessage(String.valueOf(e.getMessage()), 180));"));
        assertTrue(router.contains(
                "dd.put(\"message\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"));"));
    }

    @Test
    void policyRouterBuildFailureLogUsesHashAndLengthOnly() throws Exception {
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);

        assertFalse(router.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(router.contains("[Router] failed to build requested modelHash={} modelLength={} tier={} errorHash={} errorLength={}"));
        assertTrue(router.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void policyRouterPipelineFailureExceptionTypeDoesNotStringifyRawErrorObjects() throws Exception {
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);

        assertFalse(router.contains("failure.put(\"exceptionType\", String.valueOf(error));"));
        assertTrue(router.contains("failure.put(\"exceptionType\", safeExceptionType(error));"));
    }

    @Test
    void policyRouterModelIdentifiersUseFingerprintsInLogsAndTelemetry() throws Exception {
        String core = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/ModelRouterCore.java"),
                StandardCharsets.UTF_8);
        String router = Files.readString(
                Path.of("main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"),
                StandardCharsets.UTF_8);

        assertFalse(core.contains("Embedding/legacy model '{}'"));
        assertTrue(core.contains("requestedModelHash={} requestedModelLength={}"));
        assertTrue(core.contains("defaultModelHash={} defaultModelLength={}"));

        assertFalse(router.contains("requestedModel='{}'"));
        assertFalse(router.contains("highModel='{}'"));
        assertFalse(router.contains("TraceStore.put(\"ml.router.requestedModel\", req);"));
        assertFalse(router.contains("TraceStore.put(\"ml.router.selected\","));
        assertFalse(router.contains("TraceStore.put(\"ml.router.promote.blocked.highModel\", highName);"));
        assertFalse(router.contains("ev.put(\"requestedModel\", req);"));
        assertFalse(router.contains("ev.put(\"selected\","));
        assertFalse(router.contains("ev.put(\"highModel\", highName);"));
        assertFalse(router.contains("dd.put(\"requestedModel\", req);"));
        assertFalse(router.contains("dd.put(\"selected\","));
        assertFalse(router.contains("dd.put(\"highModel\", highName);"));

        assertTrue(router.contains("TraceStore.put(\"ml.router.requestedModelHash\", SafeRedactor.hashValue(req));"));
        assertTrue(router.contains("TraceStore.put(\"ml.router.selectedHash\", SafeRedactor.hashValue("));
        assertTrue(router.contains("TraceStore.put(\"ml.router.promote.blocked.highModelHash\", SafeRedactor.hashValue(highName));"));
        assertTrue(router.contains("putModelFingerprint(ev, \"requestedModel\", req);"));
        assertTrue(router.contains("putModelFingerprint(dd, \"selected\","));
    }

    @Test
    void routeSignalMapMasksSecretLikeReasons() {
        String fakeKey = "sk-" + "test-route-signal-secret-1234567890";
        String rawReason = "provider failed api_key=" + fakeKey;
        RouteSignal signal = new RouteSignal(
                0.4,
                0.1,
                0.2,
                0.3,
                RouteSignal.Intent.SEARCH_HEAVY,
                RouteSignal.Verbosity.NORMAL,
                2048,
                RouteSignal.Preference.QUALITY,
                rawReason);

        String rendered = signal.toSignalMap().toString();

        assertFalse(rendered.contains(fakeKey));
        assertTrue(String.valueOf(signal.toSignalMap().get("reason")).startsWith("hash:"));
    }
}
