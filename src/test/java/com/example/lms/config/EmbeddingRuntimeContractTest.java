package com.example.lms.config;

import com.example.lms.service.embedding.*;
import com.example.lms.vector.EmbeddingFingerprint;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingRuntimeContractTest {
    @Test void compositeFallbackCannotReintroduceIncompatibleOrUnstampedVectors() {
        var fp = fingerprint();
        var base = new InMemoryEmbeddingStore<TextSegment>();
        var vector = Embedding.from(new float[]{0.6f, 0.8f});
        base.add(vector, TextSegment.from("incompatible", Metadata.from("emb_fp", "ollama|other|2")));
        base.add(vector, TextSegment.from("legacy"));
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("store", base);
        org.springframework.beans.factory.ObjectProvider<dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>> provider =
                (org.springframework.beans.factory.ObjectProvider) beans.getBeanProvider(dev.langchain4j.store.embedding.EmbeddingStore.class);
        var store = new LangChainConfig(null, null).embeddingStore(null,
                provider, fp, null, "memory");
        var request = EmbeddingSearchRequest.builder().queryEmbedding(vector).maxResults(10).minScore(0.0d).build();
        assertThat(store.search(request).matches()).isEmpty();
        store.add(vector, TextSegment.from("compatible"));
        assertThat(store.search(request).matches()).extracting(m -> m.embedded().text()).containsExactly("compatible");
    }

    @Test void retainedEmbeddingCacheTracksEndpointNormalizationAndExactInput() {
        AtomicInteger calls = new AtomicInteger();
        var delegate = new OllamaEmbeddingModel(WebClient.create()) {
            @Override public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[]{calls.incrementAndGet(), 1}));
            }
        };
        ReflectionTestUtils.setField(delegate, "apiUrl", "http://127.0.0.1:11435/api/embed");
        var cached = new DecoratingEmbeddingModel(delegate, new EmbeddingCache.InMemory(), Duration.ofMinutes(1), fingerprint());
        cached.embed("same input"); cached.embed("same input");
        assertThat(calls).hasValue(1);
        ReflectionTestUtils.setField(delegate, "apiUrl", "http://127.0.0.1:21435/api/embed");
        cached.embed("same input");
        assertThat(calls).hasValue(2);
        ReflectionTestUtils.setField(delegate, "normalizationMode", "l2");
        cached.embed("same input"); cached.embed("same  input");
        assertThat(calls).hasValue(4);
    }

    @Test void equalDimensionsWithDifferentNormalizationAreDifferentIndexContracts() {
        var fp = fingerprint(); String original = fp.fingerprint();
        ReflectionTestUtils.setField(fp, "normalization", "l2");
        assertThat(fp.matches(original)).isFalse();
    }

    private static EmbeddingFingerprint fingerprint() {
        var fp = new EmbeddingFingerprint();
        ReflectionTestUtils.setField(fp, "provider", "ollama");
        ReflectionTestUtils.setField(fp, "model", "fixture");
        ReflectionTestUtils.setField(fp, "dimensions", 2);
        return fp;
    }
}
