package com.example.lms.llm.spec;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelMetadataProbeTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void parsesOllamaShowMetadataAndEndpointHost() {
        LocalModelMetadataProbe probe = new LocalModelMetadataProbe();

        ModelSpecSnapshot snapshot = probe.fromOllamaShow(
                "http://LOCALHOST:11434",
                "qwen3-embedding:4b",
                Map.of(
                        "capabilities", List.of("completion", "embedding"),
                        "details", Map.of(
                                "format", "gguf",
                                "family", "qwen3",
                                "parameter_size", "4b",
                                "quantization_level", "q4"),
                        "model_info", Map.of(
                                "qwen3.context_length", "8192",
                                "qwen3.embedding_length", 2560)));

        assertEquals("ollama", snapshot.provider());
        assertEquals("localhost", snapshot.endpointHost());
        assertEquals(8192, snapshot.contextTokens());
        assertEquals(2560, snapshot.embeddingDim());
        assertEquals(List.of("completion", "embedding"), snapshot.capabilities());
        assertTrue(snapshot.metadata().containsKey("format"));
    }

    @Test
    void malformedMetadataFailsSoftWithoutExactEmptyCatchBlocks() throws Exception {
        LocalModelMetadataProbe probe = new LocalModelMetadataProbe();

        ModelSpecSnapshot snapshot = probe.fromOllamaShow(
                "http://bad host",
                "qwen3-embedding:4b",
                Map.of("model_info", Map.of("qwen3.context_length", "not-a-number")));

        assertNull(snapshot.endpointHost());
        assertNull(snapshot.contextTokens());
        assertEquals(true, TraceStore.get("llm.gateway.spec.metadata.suppressed.endpoint_host"));
        assertEquals("invalid_url",
                TraceStore.get("llm.gateway.spec.metadata.suppressed.endpoint_host.errorType"));
        assertEquals(true, TraceStore.get("llm.gateway.spec.metadata.suppressed.model_info_int"));
        assertEquals("invalid_number",
                TraceStore.get("llm.gateway.spec.metadata.suppressed.model_info_int.errorType"));
        assertEquals("endpoint_host", TraceStore.get("llm.gateway.spec.metadata.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("llm.gateway.spec.metadata.suppressed.errorType"));

        String source = Files.readString(Path.of("main/java/com/example/lms/llm/spec/LocalModelMetadataProbe.java"));
        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "LocalModelMetadataProbe fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void malformedModelInfoLeavesAggregateBreadcrumb() {
        LocalModelMetadataProbe probe = new LocalModelMetadataProbe();

        ModelSpecSnapshot snapshot = probe.fromOllamaShow(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                Map.of("model_info", Map.of("qwen3.context_length", "not-a-number")));

        assertNull(snapshot.contextTokens());
        assertEquals("model_info_int", TraceStore.get("llm.gateway.spec.metadata.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("llm.gateway.spec.metadata.suppressed.errorType"));
    }
}
