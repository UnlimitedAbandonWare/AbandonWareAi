package com.example.lms.service.llm;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankerSelectorTest {

    @Test
    void blankBackendDefaultsToEmbeddingEvenWhenOnnxToggleIsTrue() {
        CrossEncoderReranker embedding = new TestReranker();
        CrossEncoderReranker onnx = new TestReranker();
        RerankerSelector selector = new RerankerSelector(
                rerankers(embedding, onnx),
                "",
                true,
                false);

        assertSame(embedding, selector.select());
    }

    @Test
    void explicitOnnxFallsBackWhenRuntimeGuardIsDisabled() {
        CrossEncoderReranker embedding = new TestReranker();
        CrossEncoderReranker onnx = new TestReranker();
        RerankerSelector selector = new RerankerSelector(
                rerankers(embedding, onnx),
                "onnx-runtime",
                true,
                false);

        assertSame(embedding, selector.select());
    }

    @Test
    void explicitOnnxCanSelectOnnxWhenRuntimeGuardIsEnabled() {
        CrossEncoderReranker embedding = new TestReranker();
        CrossEncoderReranker onnx = new TestReranker();
        RerankerSelector selector = new RerankerSelector(
                rerankers(embedding, onnx),
                "onnx-runtime",
                true,
                true);

        assertSame(onnx, selector.select());
    }

    @Test
    void uppercaseOnnxBackendSelectionIsIndependentOfDefaultLocale() {
        CrossEncoderReranker embedding = new TestReranker();
        CrossEncoderReranker onnx = new TestReranker();
        RerankerSelector selector;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            selector = new RerankerSelector(
                    rerankers(embedding, onnx),
                    "ONNX-RUNTIME",
                    true,
                    true);
        } finally {
            Locale.setDefault(previous);
        }

        assertSame(onnx, selector.select());
    }

    @Test
    void backendDiagnosticsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/llm/RerankerSelector.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("backend={}"));
        assertFalse(source.contains("reranker-backend={}"));
        assertFalse(source.contains("backend '{}' not found"));
        assertTrue(source.contains("backendHash={} backendLength={}"));
        assertTrue(source.contains("rerankerBackendHash={} rerankerBackendLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(backend)"));
    }

    private static Map<String, CrossEncoderReranker> rerankers(
            CrossEncoderReranker embedding,
            CrossEncoderReranker onnx) {
        Map<String, CrossEncoderReranker> rerankers = new LinkedHashMap<>();
        rerankers.put("embeddingCrossEncoderReranker", embedding);
        rerankers.put("onnxCrossEncoderReranker", onnx);
        return rerankers;
    }

    private static final class TestReranker implements CrossEncoderReranker {
        @Override
        public List<Content> rerank(String query, List<Content> candidates, int topN) {
            return candidates == null ? List.of() : candidates;
        }
    }
}
