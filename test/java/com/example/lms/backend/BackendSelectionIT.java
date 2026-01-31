package com.example.lms.backend;

import com.example.lms.service.llm.RerankerSelector;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.OnnxCrossEncoderReranker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;



import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that verifies the {@link RerankerSelector} returns the
 * correct bean based on the configured backend.  When the backend is set
 * to {@code onnx-runtime} the selector should yield an instance of
 * {@link OnnxCrossEncoderReranker}.  Other backends are not tested here
 * but will fall back to alternative implementations.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "abandonware.reranker.backend=onnx-runtime"
})
public class BackendSelectionIT {

    @Autowired
    private RerankerSelector selector;

    @Test
    void selectsOnnxRerankerWhenBackendConfigured() {
        CrossEncoderReranker r = selector.select();
        // The exact class may be a CGLIB proxy - use assignable check
        assertTrue(r instanceof OnnxCrossEncoderReranker,
                "Expected an OnnxCrossEncoderReranker when backend=onnx-runtime");
    }
}