package com.example.lms.service.embedding;

import com.example.lms.search.TraceStore;
import com.example.lms.vector.EmbeddingFingerprint;
import com.example.lms.vector.FingerprintAwareEmbeddingStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbeddingVectorUnderflowContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shortResponseNeverReachesCacheOrStoreAndRecoveryStillWorks(boolean batch) {
        Fixture f = new Fixture(new float[]{0.5f, 0.75f}, false);
        String input = "synthetic-underflow";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> f.rawEmbedding(input, batch));
        assertFalse(failure.getMessage().contains(input));
        for (int attempt = 0; attempt < 2; attempt++) {
            int before = f.requests.get();
            assertThrows(IllegalStateException.class, () -> f.addAndSearch(input, batch));
            assertTrue(f.requests.get() > before, "failed input must be recomputed, not reused from cache");
            assertTrue(f.cacheEntries().isEmpty());
            verifyNoInteractions(f.store);
        }

        f.response.set(new float[]{0.5f, 0.75f, 0.25f, 0.125f});
        assertEquals(1, f.addAndSearch("synthetic-recovery", batch).matches().size());
        assertEquals(1, f.addAndSearch(input, batch).matches().size());
        verify(f.store, times(2)).add(any(Embedding.class), any(TextSegment.class));
        verify(f.store, times(2)).search(any(EmbeddingSearchRequest.class));
        assertEquals(f.fingerprint.fingerprint(),
                f.savedSegment.get().metadata().getString(EmbeddingFingerprint.META_EMB_FP));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactResponseUsesSameFingerprintAndThenHitsCache(boolean batch) {
        Fixture f = new Fixture(new float[]{0.5f, 0.75f, 0.25f, 0.125f}, false);
        assertNormalAndCached(f, batch);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longerResponseKeepsPrefixAndSameFingerprint(boolean batch) {
        Fixture f = new Fixture(new float[]{0.5f, 0.75f, 0.25f, 0.125f, 0.9f, 0.8f}, false);
        assertNormalAndCached(f, batch);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitZeroPadOptInStillReachesStoreAndCache(boolean batch) {
        Fixture f = new Fixture(new float[]{0.5f, 0.75f}, true);
        assertEquals(1, f.addAndSearch("synthetic-opt-in", batch).matches().size());
        float[] vector = f.savedVector.get().vector();
        assertEquals(4, vector.length);
        assertEquals(0.5f, vector[0]);
        assertEquals(0.75f, vector[1]);
        assertEquals(0f, vector[2]);
        assertEquals(0f, vector[3]);
        int before = f.requests.get();
        f.addAndSearch("synthetic-opt-in", batch);
        assertEquals(before, f.requests.get());
        verify(f.store, times(2)).add(any(Embedding.class), any(TextSegment.class));
        verify(f.store, times(2)).search(any(EmbeddingSearchRequest.class));
    }

    private static void assertNormalAndCached(Fixture f, boolean batch) {
        assertEquals(1, f.addAndSearch("synthetic-normal", batch).matches().size());
        float[] vector = f.savedVector.get().vector();
        assertEquals(4, vector.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(f.response.get()[i], vector[i]);
        }
        assertEquals(f.fingerprint.fingerprint(),
                f.savedSegment.get().metadata().getString(EmbeddingFingerprint.META_EMB_FP));
        assertFalse(f.cacheEntries().isEmpty());
        int before = f.requests.get();
        f.addAndSearch("synthetic-normal", batch);
        assertEquals(before, f.requests.get());
        verify(f.store, times(2)).add(any(Embedding.class), any(TextSegment.class));
        verify(f.store, times(2)).search(any(EmbeddingSearchRequest.class));
    }

    private static final class Fixture {
        final AtomicReference<float[]> response;
        final AtomicInteger requests = new AtomicInteger();
        final OllamaEmbeddingModel primary;
        final EmbeddingCache.InMemory cache = new EmbeddingCache.InMemory();
        final EmbeddingFingerprint fingerprint = new EmbeddingFingerprint();
        final DecoratingEmbeddingModel decorated;
        final EmbeddingStore<TextSegment> store;
        final FingerprintAwareEmbeddingStore fingerprintStore;
        final AtomicReference<TextSegment> savedSegment = new AtomicReference<>();
        final AtomicReference<Embedding> savedVector = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        Fixture(float[] vector, boolean allowZeroPad) {
            TraceStore.clear();
            response = new AtomicReference<>(vector);
            // ExchangeFunction intercepts every request; no connector, init, health or warmup runs.
            WebClient client = WebClient.builder().exchangeFunction(request -> {
                assertEquals(HttpMethod.POST, request.method());
                assertEquals("/api/embed", request.url().getPath());
                requests.incrementAndGet();
                try {
                    String json = new ObjectMapper().writeValueAsString(Map.of("embeddings", List.of(response.get())));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(json).build());
                } catch (JsonProcessingException error) {
                    return Mono.error(error);
                }
            }).build();
            primary = new OllamaEmbeddingModel(client);
            ReflectionTestUtils.setField(primary, "provider", "ollama");
            ReflectionTestUtils.setField(primary, "model", "synthetic-model");
            ReflectionTestUtils.setField(primary, "apiUrl", "http://127.0.0.1:11434/api/embed");
            ReflectionTestUtils.setField(primary, "dimensions", 4);
            ReflectionTestUtils.setField(primary, "dimensionGuardMode", "WARN_ONLY");
            ReflectionTestUtils.setField(primary, "allowZeroPad", allowZeroPad);
            ReflectionTestUtils.setField(primary, "timeoutSec", 5);
            ReflectionTestUtils.setField(primary, "fastFailEnabled", false);
            ReflectionTestUtils.setField(primary, "fastFailHealthEnabled", false);
            ReflectionTestUtils.setField(primary, "portFallbackEnabled", false);
            ReflectionTestUtils.setField(primary, "crossGpuFallbackEnabled", false);
            ReflectionTestUtils.setField(fingerprint, "provider", "ollama");
            ReflectionTestUtils.setField(fingerprint, "model", "synthetic-model");
            ReflectionTestUtils.setField(fingerprint, "dimensions", 4);
            ReflectionTestUtils.setField(fingerprint, "normalization", "SLICE_TO_CONFIGURED_DIM");
            decorated = new DecoratingEmbeddingModel(primary, cache, Duration.ofMinutes(5), fingerprint);
            store = mock(EmbeddingStore.class);
            when(store.add(any(Embedding.class), any(TextSegment.class))).thenAnswer(invocation -> {
                savedVector.set(invocation.getArgument(0));
                savedSegment.set(invocation.getArgument(1));
                return "synthetic-id";
            });
            when(store.search(any(EmbeddingSearchRequest.class))).thenAnswer(invocation -> {
                EmbeddingSearchRequest request = invocation.getArgument(0);
                assertEquals(4, request.queryEmbedding().vector().length);
                return new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(0.99d, "synthetic-id", savedVector.get(), savedSegment.get())));
            });
            fingerprintStore = new FingerprintAwareEmbeddingStore(store, fingerprint);
        }

        Embedding rawEmbedding(String text, boolean batch) {
            return batch ? primary.embedAll(List.of(TextSegment.from(text))).content().get(0)
                    : primary.embed(text).content();
        }

        EmbeddingSearchResult<TextSegment> addAndSearch(String text, boolean batch) {
            TextSegment segment = TextSegment.from(text);
            Embedding vector = batch ? decorated.embedAll(List.of(segment)).content().get(0)
                    : decorated.embed(text).content();
            // Execute the existing production validation boundary without Spring or service initialization.
            ReflectionTestUtils.invokeMethod(com.example.lms.service.VectorStoreService.class,
                    "validateEmbeddingsOrThrow", List.of(vector), List.of(segment));
            fingerprintStore.add(vector, segment);
            return fingerprintStore.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(vector).maxResults(1).minScore(0d).build());
        }

        Map<?, ?> cacheEntries() {
            return (Map<?, ?>) ReflectionTestUtils.getField(cache, "map");
        }
    }
}
