package com.example.lms.api;

import com.example.lms.config.PineconeProps;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
import com.example.lms.test.SecretFixtures;
import com.example.lms.vector.EmbeddingFingerprint;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorDiagnosticsControllerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void diagnosticsExposeCurrentEmbeddingDimensionWithoutRawFingerprint() {
        EmbeddingFingerprint fingerprint = fingerprint(1536);
        UpstashVectorStoreAdapter upstash = mock(UpstashVectorStoreAdapter.class);
        String rawApiKey = SecretFixtures.openAiKey();
        String rawQuery = "private vector search query about patient-42";
        when(upstash.effectiveMode()).thenReturn(Map.of(
                "configured", true,
                "readEnabled", true,
                "writeRequested", false,
                "readOnly", true,
                "writeEnabled", false,
                "namespace", "rag",
                "endpointHost", "https://upstash.example.invalid",
                "apiKey", rawApiKey,
                "rawQuery", rawQuery));
        when(upstash.isConfigured()).thenReturn(true);
        when(upstash.isWriteEnabled()).thenReturn(false);
        when(upstash.namespace()).thenReturn("rag");
        when(upstash.restUrlHost()).thenReturn("https://upstash.example.invalid");

        VectorDiagnosticsController controller = controller(fingerprint, provider(null), provider(upstash));

        Map<String, Object> body = controller.diagnostics().getBody();

        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> embedding = (Map<String, Object>) body.get("embedding");
        @SuppressWarnings("unchecked")
        Map<String, Object> vector = (Map<String, Object>) body.get("vector");
        @SuppressWarnings("unchecked")
        Map<String, Object> upstashMode = (Map<String, Object>) vector.get("upstash");

        assertEquals(1536, embedding.get("dimensions"));
        assertTrue(String.valueOf(embedding.get("fingerprintHash")).startsWith("hash:"));
        assertFalse(String.valueOf(embedding).contains("qwen3-embedding:4b"));
        assertEquals(true, upstashMode.get("configured"));
        assertEquals(true, upstashMode.get("readEnabled"));
        assertEquals(true, upstashMode.get("readOnly"));
        assertEquals(false, upstashMode.get("writeEnabled"));
        assertEquals("(redacted)", upstashMode.get("apiKey"));
        assertTrue(upstashMode.get("rawQuery") instanceof Map<?, ?>);
        assertFalse(upstashMode.toString().contains(rawApiKey));
        assertFalse(upstashMode.toString().contains(rawQuery));
    }

    @Test
    void upstashInfoComparesRemoteDimensionToConfiguredEmbeddingDimension() {
        EmbeddingFingerprint fingerprint = fingerprint(1536);
        UpstashVectorStoreAdapter upstash = mock(UpstashVectorStoreAdapter.class);
        Map<String, Object> remoteInfo = new LinkedHashMap<>();
        remoteInfo.put("dimension", 1536);
        remoteInfo.put("vectorCount", 42);
        when(upstash.isConfigured()).thenReturn(true);
        when(upstash.indexInfo()).thenReturn(remoteInfo);

        VectorDiagnosticsController controller = controller(fingerprint, provider(null), provider(upstash));

        ResponseEntity<Map<String, Object>> response = controller.upstashInfo();
        Map<String, Object> body = response.getBody();

        assertNotNull(body);
        assertEquals(1536, body.get("dimension"));
        assertEquals(1536, body.get("configuredEmbeddingDimensions"));
        assertEquals(1536, body.get("indexDimensions"));
        assertEquals("dimension", body.get("indexDimensionsSource"));
        assertEquals(true, body.get("dimensionMatchesConfigured"));
    }

    @Test
    void upstashInfoKeepsStringDimensionParseableAfterSafeModeRedaction() {
        EmbeddingFingerprint fingerprint = fingerprint(1536);
        UpstashVectorStoreAdapter upstash = mock(UpstashVectorStoreAdapter.class);
        Map<String, Object> remoteInfo = new LinkedHashMap<>();
        remoteInfo.put("dimension", "1536");
        remoteInfo.put("apiKey", SecretFixtures.openAiKey());
        when(upstash.isConfigured()).thenReturn(true);
        when(upstash.indexInfo()).thenReturn(remoteInfo);

        VectorDiagnosticsController controller = controller(fingerprint, provider(null), provider(upstash));

        ResponseEntity<Map<String, Object>> response = controller.upstashInfo();
        Map<String, Object> body = response.getBody();

        assertNotNull(body);
        assertEquals(1536, body.get("dimension"));
        assertEquals(1536, body.get("indexDimensions"));
        assertEquals(true, body.get("dimensionMatchesConfigured"));
        assertEquals("(redacted)", body.get("apiKey"));
        assertFalse(body.toString().contains(SecretFixtures.openAiKey()));
    }

    @Test
    void upstashInfoLeavesBreadcrumbWhenDimensionParseFails() {
        EmbeddingFingerprint fingerprint = fingerprint(1536);
        UpstashVectorStoreAdapter upstash = mock(UpstashVectorStoreAdapter.class);
        Map<String, Object> remoteInfo = new LinkedHashMap<>();
        remoteInfo.put("dimension", "not-a-number");
        when(upstash.isConfigured()).thenReturn(true);
        when(upstash.indexInfo()).thenReturn(remoteInfo);

        VectorDiagnosticsController controller = controller(fingerprint, provider(null), provider(upstash));

        Map<String, Object> body = controller.upstashInfo().getBody();

        assertNotNull(body);
        assertEquals(null, body.get("dimensionMatchesConfigured"));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.diagnostics.suppressed.dimensionParse"));
        assertEquals("invalid_number",
                TraceStore.get("vector.diagnostics.suppressed.dimensionParse.errorType"));
        assertEquals("dimensionParse", TraceStore.get("vector.diagnostics.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("vector.diagnostics.suppressed.errorType"));
    }

    @Test
    void upstashInfoFlagsRemoteDimensionMismatchAgainstConfiguredEmbeddingDimension() {
        EmbeddingFingerprint fingerprint = fingerprint(1536);
        UpstashVectorStoreAdapter upstash = mock(UpstashVectorStoreAdapter.class);
        Map<String, Object> remoteInfo = new LinkedHashMap<>();
        remoteInfo.put("dimension", 2560);
        when(upstash.isConfigured()).thenReturn(true);
        when(upstash.indexInfo()).thenReturn(remoteInfo);

        VectorDiagnosticsController controller = controller(fingerprint, provider(null), provider(upstash));

        Map<String, Object> body = controller.upstashInfo().getBody();

        assertNotNull(body);
        assertEquals(1536, body.get("configuredEmbeddingDimensions"));
        assertEquals(2560, body.get("indexDimensions"));
        assertEquals("dimension", body.get("indexDimensionsSource"));
        assertEquals(false, body.get("dimensionMatchesConfigured"));
    }

    private static VectorDiagnosticsController controller(
            EmbeddingFingerprint fingerprint,
            ObjectProvider<VectorStoreService> vectorStoreProvider,
            ObjectProvider<UpstashVectorStoreAdapter> upstashProvider) {
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        PineconeProps pineconeProps = mock(PineconeProps.class);
        return new VectorDiagnosticsController(
                fingerprint,
                store,
                pineconeProps,
                vectorStoreProvider,
                upstashProvider);
    }

    private static EmbeddingFingerprint fingerprint(int dimensions) {
        EmbeddingFingerprint fingerprint = new EmbeddingFingerprint();
        ReflectionTestUtils.setField(fingerprint, "provider", "ollama");
        ReflectionTestUtils.setField(fingerprint, "model", "qwen3-embedding:4b");
        ReflectionTestUtils.setField(fingerprint, "dimensions", dimensions);
        ReflectionTestUtils.setField(fingerprint, "enabled", true);
        ReflectionTestUtils.setField(fingerprint, "allowLegacy", false);
        ReflectionTestUtils.setField(fingerprint, "bypassIfMetadataMissing", true);
        return fingerprint;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
