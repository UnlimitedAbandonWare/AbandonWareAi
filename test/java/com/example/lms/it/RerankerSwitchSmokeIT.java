package com.example.lms.it;

import com.example.lms.service.onnx.OnnxRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;



/**
 * Smoke integration test that verifies the actuator health endpoint reflects
 * the availability of the ONNX model.  When the ONNX model is present and
 * successfully initialised the reranker health detail should report
 * {@code reranker=onnx}.  When the model is missing or fails to load the
 * reranker detail should report
 * {@code reranker=onnx-unavailable-fallback-embedding}.  This test does not
 * assert the HTTP layer; rather it uses the {@link HealthEndpoint} to
 * interrogate the in-memory health details.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "abandonware.reranker.backend=onnx-runtime",
    "onnx.model-path=classpath:/models/your-cross-encoder.onnx"
})
class RerankerSwitchSmokeIT {
    @Autowired(required = false)
    OnnxRuntimeService onnx;
    @Autowired
    HealthEndpoint health;

    @Test
    void healthReflectsOnnxAvailability_orFallback() {
        var h = health.health();
        if (onnx != null && onnx.available()) {
            assert h.getDetails().get("reranker").equals("onnx");
        } else {
            assert h.getDetails().get("reranker").equals("onnx-unavailable-fallback-embedding");
        }
    }
}