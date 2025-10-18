package com.abandonware.ai.service.onnx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * ONNX runtime loader via reflection to avoid compile-time dependency.
 */
@Service
@ConditionalOnProperty(prefix = "onnx", name = "enabled", havingValue = "true")
public class OnnxRuntimeService {

    @Value("${onnx.model.path:}")
    private String modelPath;

    public boolean isReady() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return modelPath != null && !modelPath.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
