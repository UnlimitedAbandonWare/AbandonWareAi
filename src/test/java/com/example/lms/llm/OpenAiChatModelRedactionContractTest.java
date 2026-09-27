package com.example.lms.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatModelRedactionContractTest {

    @Test
    void chatModelInterfaceUsesStageSpecificPromptName() throws Exception {
        Path source = Path.of("main/java/com/example/lms/llm/ChatModel.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("generate(String prompt"));
        assertFalse(code.contains("generate(prompt)"));
        assertTrue(code.contains("generate(String llmGenerationPrompt"));
        assertTrue(code.contains("generate(llmGenerationPrompt)"));
    }

    @Test
    void openAiChatModelUsesStageSpecificPromptName() throws Exception {
        Path source = Path.of("main/java/com/example/lms/llm/OpenAiChatModel.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("generate(String prompt"));
        assertFalse(code.contains("return generate(prompt,"));
        assertFalse(code.contains("\"content\", prompt"));
        assertTrue(code.contains("generate(String llmGenerationPrompt"));
        assertTrue(code.contains("return generate(llmGenerationPrompt,"));
        assertTrue(code.contains("\"content\", llmGenerationPrompt"));
    }

    @Test
    void completionFailureLogDoesNotWriteRawThrowableText() throws Exception {
        Path source = Path.of("main/java/com/example/lms/llm/OpenAiChatModel.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = code.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains("completion failed failureClass={} errorType={}"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void tokenParamRetryCatchLeavesScannerVisibleBreadcrumb() throws Exception {
        Path source = Path.of("main/java/com/example/lms/llm/OpenAiChatModel.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertTrue(code.contains("TraceStore.put(\"llm.gateway.openai.tokenParamRetry\", true)"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(wcre.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void publicOpenAiPlaceholderApiKeyFailsSoftWithoutOutboundCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"choices\":[{\"message\":{\"content\":\"remote\"}}]}")
                            .build());
                })
                .build();
        OpenAiChatModel model = new OpenAiChatModel(client);
        ReflectionTestUtils.setField(model, "apiKey", "sk-local");
        ReflectionTestUtils.setField(model, "baseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(model, "defaultModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(model, "ownerToken", "");
        ReflectionTestUtils.setField(model, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(model, "allowedHosts", "");
        ReflectionTestUtils.setField(model, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(model, "throwOnEmptyFailure", false);

        model.normaliseAndValidate();
        String out = model.generate("hello");

        assertEquals("", out);
        assertEquals(0, calls.get());
    }

    @Test
    void localModelOfficialEndpointGuardIsIndependentOfDefaultLocale() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("startup validation must not call the provider");
                })
                .build();
        OpenAiChatModel model = new OpenAiChatModel(client);
        ReflectionTestUtils.setField(model, "apiKey", "");
        ReflectionTestUtils.setField(model, "baseUrl", "HTTPS://API.OPENAI.COM");
        ReflectionTestUtils.setField(model, "defaultModel", "QWEN2.5");
        ReflectionTestUtils.setField(model, "ownerToken", "");
        ReflectionTestUtils.setField(model, "allowedHosts", "");
        ReflectionTestUtils.setField(model, "requireAuthForRemote", true);

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThrows(IllegalStateException.class, model::normaliseAndValidate);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
