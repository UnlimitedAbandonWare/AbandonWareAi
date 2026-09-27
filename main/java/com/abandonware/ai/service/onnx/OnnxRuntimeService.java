package com.abandonware.ai.service.onnx;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OnnxRuntimeService {

    @Value("${onnx.enabled:false}")
    private boolean enabled;

    @Value("${onnx.model.path:}")
    private String modelPath;

    public boolean isReady() {
        if (enabled) {
            TraceStore.put("onnx.abandonware.disabledReason", disabledReason());
        }
        return false;
    }

    public float[] scoreBatch(long[][] inputIds, long[][] attnMask, long[][] tokenTypeIds) {
        int n = inputIds == null ? 0 : inputIds.length;
        TraceStore.put("onnx.abandonware.scoreBatch.skipped", true);
        TraceStore.put("onnx.abandonware.disabledReason", disabledReason());
        return new float[Math.max(0, n)];
    }

    private String disabledReason() {
        return modelPath == null || modelPath.isBlank()
                ? "model_path_missing"
                : "runtime_dependency_unavailable";
    }
}
