package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicChatModelFactoryRedactionContractTest {

    @Test
    void samplingAdjustmentLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertFalse(source.contains("for model={}\""));
        assertFalse(source.contains("safeTemp, effectiveModel"));
        assertFalse(source.contains("safeTopP, effectiveModel"));
        assertFalse(source.contains("safeFreqPenalty,\r\n                        effectiveModel"));
        assertFalse(source.contains("safePresencePenalty,\r\n                        effectiveModel"));
        assertTrue(source.contains("modelHash={} modelLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(effectiveModel)"));
    }

    @Test
    void traceBreadcrumbsDoNotWriteRawModelOrBaseUrlValues() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertFalse(source.contains("TraceStore.put(\"llm.factory.model.raw\", rawModel)"));
        assertFalse(source.contains("TraceStore.put(\"llm.factory.model.effective\", effectiveModel)"));
        assertFalse(source.contains("TraceStore.put(\"llm.factory.baseUrl\", baseUrl)"));
        assertTrue(source.contains("TraceStore.put(\"llm.factory.model.rawHash\", SafeRedactor.hashValue(rawModel))"));
        assertTrue(source.contains("TraceStore.put(\"llm.factory.model.effectiveHash\", SafeRedactor.hashValue(effectiveModel))"));
        assertTrue(source.contains("TraceStore.put(\"llm.factory.baseUrlHost\""));
        assertTrue(source.contains("LocalLlmGatewaySecurity.endpointHost(baseUrl)"));
        assertTrue(source.contains("TraceStore.put(\"llm.factory.baseUrlHash\", SafeRedactor.hashValue(baseUrl))"));
    }

    @Test
    void traceBreadcrumbFallbackLogsSafeErrorType() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertTrue(source.contains("DynamicChatModelFactory: trace breadcrumbs skipped errorType={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void connectionWrapperExceptionDoesNotExposeRawBaseUrlOrMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertFalse(source.contains(
                "\"Failed to build chat model for baseUrl=\" + baseUrl + \": \" + e.getMessage()"));
        assertTrue(source.contains("\"Failed to build chat model baseUrlHost=\""));
        assertTrue(source.contains("+ LocalLlmGatewaySecurity.endpointHost(baseUrl)"));
        assertTrue(source.contains(
                "+ \" baseUrlHash=\" + SafeRedactor.hashValue(baseUrl)"));
        assertTrue(source.contains(
                "+ \" error=\" + SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\")"));
    }

    @Test
    void localBuilderKeyFallbackUsesPlaceholderAwareGuard() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertFalse(source.contains("(apiKeyForCall == null || apiKeyForCall.isBlank()) ? \"ollama\" : apiKeyForCall"));
        assertTrue(source.contains("import com.example.lms.config.ConfigValueGuards;"));
        assertTrue(source.contains("ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKeyForCall)"));
    }

    @Test
    void springConstructorIsExplicitWhenRuntimeHealthTrackerIsFinal() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertFalse(source.contains("@RequiredArgsConstructor"));
        assertTrue(source.contains("import org.springframework.beans.factory.annotation.Autowired;"));
        assertTrue(source.contains("@Autowired\n    public DynamicChatModelFactory(Environment env,"));
        assertTrue(source.contains("ModelRuntimeHealthTracker modelRuntimeHealthTracker)"));
        assertTrue(source.contains("this.modelRuntimeHealthTracker = modelRuntimeHealthTracker == null"));
    }
}
