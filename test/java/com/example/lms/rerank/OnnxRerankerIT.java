package com.example.lms.rerank;

import com.example.lms.service.onnx.OnnxRuntimeService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;



import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration test that exercises the ONNX reranker and verifies that
 * different query/document pairs produce different scores.  This test
 * implicitly ensures that the model is invoked (when available) and
 * distinguishes between matching and non-matching inputs.  When the
 * ONNX model is unavailable the test is skipped.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "abandonware.reranker.backend=onnx-runtime",
        "abandonware.reranker.onnx.model-path=classpath:/models/your-cross-encoder.onnx"
})
public class OnnxRerankerIT {
    @Autowired(required = false)
    private OnnxRuntimeService onnx;

    @Test
    void onnxProducesDifferentScoresForDifferentInputs() {
        // Skip test when the ONNX backend is not available
        Assumptions.assumeTrue(onnx != null && onnx.available(),
                "ONNX model unavailable - skipping reranker difference test");
        double same = onnx.scorePair("apple", "apple");
        double diff = onnx.scorePair("apple", "banana");
        assertNotEquals(same, diff, 1e-6);
    }
}