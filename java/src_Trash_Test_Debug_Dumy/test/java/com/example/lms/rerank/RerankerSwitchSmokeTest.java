package com.example.lms.rerank;

import com.example.lms.service.llm.RerankerSelector;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests that ensure the reranker backend property selects the correct
 * CrossEncoderReranker bean.  The selector should map the backend name to the
 * corresponding bean by name and fall back gracefully when requested beans
 * are unavailable.  These tests use nested classes with {@link TestPropertySource}
 * to override the backend property for each case.
 */
@SpringBootTest
class RerankerSwitchSmokeTest {

    @Nested
    @TestPropertySource(properties = {
            "abandonware.reranker.backend=embedding"
    })
    class EmbeddingBackend {
        @Autowired
        RerankerSelector selector;
        @Autowired
        Map<String, CrossEncoderReranker> rerankers;

        @Test
        void selectsEmbeddingReranker() {
            CrossEncoderReranker selected = selector.select();
            assertThat(selected).isSameAs(rerankers.get("embeddingCrossEncoderReranker"));
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "abandonware.reranker.backend=onnx-runtime"
    })
    class OnnxBackend {
        @Autowired
        RerankerSelector selector;
        @Autowired
        Map<String, CrossEncoderReranker> rerankers;

        @Test
        void selectsOnnxReranker() {
            CrossEncoderReranker selected = selector.select();
            assertThat(selected).isSameAs(rerankers.get("onnxCrossEncoderReranker"));
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "abandonware.reranker.backend=noop"
    })
    class NoopBackend {
        @Autowired
        RerankerSelector selector;
        @Autowired
        Map<String, CrossEncoderReranker> rerankers;

        @Test
        void selectsNoopReranker() {
            CrossEncoderReranker selected = selector.select();
            assertThat(selected).isSameAs(rerankers.get("noopCrossEncoderReranker"));
        }
    }
}