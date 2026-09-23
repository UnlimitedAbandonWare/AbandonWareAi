package com.example.lms.plugin.image;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenAiImageServiceErrorRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void providerErrorBodiesAreNotStoredOrLoggedRaw() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"));

        assertFalse(source.contains("body.substring"));
        assertFalse(source.contains("body.replaceAll(\"\\\\s+\""));
        assertFalse(source.contains("Provider error with 200: "));
        assertFalse(source.contains("rid={} body={}"));
        assertTrue(source.contains("\"image.error.bodyHash\""));
        assertTrue(source.contains("\"image.error.bodyLength\""));
        assertTrue(source.contains("SafeRedactor.hashValue(body)"));
        assertTrue(source.contains("[AWX][image][openai] response exception caught errorType={}"));
        assertTrue(source.contains("traceTelemetrySkipped(\"response_body\", bodyError);"));
        assertTrue(source.contains("traceTelemetrySkipped(\"meta_or_default\", ex);"));
        assertTrue(source.contains("recordProviderBodyError(\"OPENAI_\" + status, body, rid)"));
    }

    @Test
    void imageGenerationRequestLogDoesNotWriteRawModelIdentifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"));

        assertFalse(source.contains("img.gen req model={} promptHash={}"));
        assertTrue(source.contains("img.gen req modelHash={} modelLength={} promptHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(model)"));
    }

    @Test
    void groundedPromptFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(source.contains("generateImages: grounding prompt failed errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex))"));
        assertTrue(source.contains("messageLength(ex)"));
    }

    @Test
    void openAiImageServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }

    @Test
    void placeholderApiKeyFailsSoftWithoutOutboundCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"data\":[{\"url\":\"https://example.invalid/image.png\"}]}")
                            .build());
                })
                .build();
        OpenAiImageService service = new OpenAiImageService(
                new OpenAiImageProperties(true, "/v1/images", "sk-local"),
                null,
                client,
                mock(ObjectProvider.class));

        List<String> out = service.generateImages("prompt", 1, "1024x1024");

        assertTrue(out.isEmpty());
        assertEquals(0, calls.get());
        assertFalse(service.isConfigured());
        assertEquals(Boolean.TRUE, TraceStore.get("image.openai.providerDisabled"));
        assertEquals("missing_openai_api_key", TraceStore.get("image.openai.disabledReason"));
    }

    @Test
    void imageJobManifestDoesNotWriteRawReason() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/jobs/ImageManifestWriter.java"));

        assertFalse(source.contains("out.put(\"reason\", job.getReason());"));
        assertFalse(source.contains("out.put(\"reason\", SafeRedactor.safeMessage(job.getReason(), 180));"));
        assertTrue(source.contains("out.put(\"reason\", SafeRedactor.traceLabelOrFallback(job.getReason(), \"unknown\"));"));
        assertTrue(source.contains("Image manifest write skipped jobIdHash={} jobIdLength={} errorType={}"));
        assertTrue(source.contains("Image manifest read skipped jobIdHash={} jobIdLength={} errorType={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(job.getId())"));
    }
}
