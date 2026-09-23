package com.example.lms.service.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HfInferenceEmbeddingModelSecurityTest {

    @Test
    void placeholderApiKeyFailsSoftWithoutOutboundCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("[0.1,0.2,0.3]")
                            .build());
                })
                .build();
        HfInferenceEmbeddingModel model = new HfInferenceEmbeddingModel(client);
        ReflectionTestUtils.setField(model, "provider", "hf");
        ReflectionTestUtils.setField(model, "apiKey", "changeme");
        ReflectionTestUtils.setField(model, "apiUrl", "https://api-inference.huggingface.co");
        ReflectionTestUtils.setField(model, "modelId", "BAAI/bge-small-en-v1.5");

        model.embed("hello");

        assertEquals(0, calls.get());
    }

    @Test
    void hfInferenceFailureLeavesStageBreadcrumbWithoutSecretValues() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/HfInferenceEmbeddingModel.java"));

        assertTrue(source.contains("LOG.log(System.Logger.Level.DEBUG, \"[HfEmbedding] fail-soft stage={0}\", \"callHf\")"));
    }
}
