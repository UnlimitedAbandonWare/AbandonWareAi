package com.example.lms.it;

import com.example.lms.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;



/**
 * Test that verifies the application context loads and does not fail when the
 * ONNX model is configured but the file is missing. The ChatService should
 * still be available, indicating a safe fallback to the embedding reranker.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "abandonware.reranker.backend=onnx-runtime",
        "onnx.model-path=classpath:/models/your-cross-encoder.onnx"
})
class OnnxAvailabilityFallbackIT {
    @Autowired
    ChatService chat;

    @Test
    void contextLoads_andFallbackIfModelMissing() {
        // accessing the service ensures context startup; should not throw
        chat.toString();
    }
}