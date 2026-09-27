package com.example.lms.vector;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorFingerprintRedactionContractTest {

    @Test
    void incompatibleDominantFingerprintDoesNotBecomeFallbackEvidence() {
        var fingerprint = new EmbeddingFingerprint();
        org.springframework.test.util.ReflectionTestUtils.setField(fingerprint, "provider", "ollama");
        org.springframework.test.util.ReflectionTestUtils.setField(fingerprint, "model", "model-a");
        org.springframework.test.util.ReflectionTestUtils.setField(fingerprint, "dimensions", 2);
        var base = new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<dev.langchain4j.data.segment.TextSegment>();
        var vector = dev.langchain4j.data.embedding.Embedding.from(new float[]{0.6f, 0.8f});
        base.add(vector, dev.langchain4j.data.segment.TextSegment.from("other-space evidence",
                dev.langchain4j.data.document.Metadata.from(EmbeddingFingerprint.META_EMB_FP, "ollama|model-b|2")));
        var guarded = new FingerprintAwareEmbeddingStore(base, fingerprint);
        var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                .queryEmbedding(vector).maxResults(3).minScore(0.0d).build();
        assertTrue(guarded.search(request).matches().isEmpty(), "same dimension does not make another model comparable");
        guarded.add(vector, dev.langchain4j.data.segment.TextSegment.from("same-space evidence"));
        var matches = guarded.search(request).matches();
        org.junit.jupiter.api.Assertions.assertEquals(1, matches.size());
        org.junit.jupiter.api.Assertions.assertEquals("same-space evidence", matches.get(0).embedded().text());
    }

    @Test
    void missingFingerprintCannotSilentlyBypassToWriterButExplicitLegacyAllowanceRemains() {
        var fingerprint = new EmbeddingFingerprint();
        var base = new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<dev.langchain4j.data.segment.TextSegment>();
        var vector = dev.langchain4j.data.embedding.Embedding.from(new float[]{0.6f, 0.8f});
        base.add(vector, dev.langchain4j.data.segment.TextSegment.from("legacy evidence"));
        var writer = org.mockito.Mockito.mock(dev.langchain4j.store.embedding.EmbeddingStore.class);
        var guarded = new FingerprintAwareEmbeddingStore(base, fingerprint, writer);
        var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                .queryEmbedding(vector).maxResults(3).minScore(0.0d).build();
        assertTrue(guarded.search(request).matches().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(writer);
        org.springframework.test.util.ReflectionTestUtils.setField(fingerprint, "allowLegacy", true);
        org.junit.jupiter.api.Assertions.assertEquals(1, guarded.search(request).matches().size());
    }

    @Test
    void fingerprintDiagnosticsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FingerprintAwareEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"vector.fp.want\", want);"));
        assertFalse(source.contains("want='{}'"));
        assertFalse(source.contains("dominant fp='{}'"));
        assertFalse(source.contains("gotSample='{}'"));
        assertTrue(source.contains("TraceStore.put(\"vector.fp.wantHash\", SafeRedactor.hashValue(want));"));
        assertTrue(source.contains("wantHash={} wantLength={}"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] metadata read skipped errorType={}"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] segment text read skipped"));
        assertTrue(source.contains("[AWX2AF2][vector][fingerprint] stamp metadata read skipped"));
    }
}
