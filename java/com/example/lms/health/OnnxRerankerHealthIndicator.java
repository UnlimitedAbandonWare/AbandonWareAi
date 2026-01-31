package com.example.lms.health;

import com.example.lms.service.onnx.OnnxRuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;



@Component
@ConditionalOnProperty(prefix = "abandonware.reranker", name = "backend", havingValue = "onnx-runtime")
public class OnnxRerankerHealthIndicator implements HealthIndicator {

    private final ObjectProvider<OnnxRuntimeService> onnx;

    public OnnxRerankerHealthIndicator(ObjectProvider<OnnxRuntimeService> onnx) {
        this.onnx = onnx;
    }

    @Override
    public Health health() {
        OnnxRuntimeService svc = onnx.getIfAvailable();
        if (svc == null) {
            return Health.unknown()
                    .withDetail("backend", "onnx")
                    .withDetail("reason", "NOT_CONFIGURED")
                    .build();
        }
        // gather details about the ONNX reranker
        Map<String, Object> details = new HashMap<>();
        details.put("backend", "onnx");
        boolean avail = svc.isAvailable();
        details.put("available", avail);
        boolean fallback = svc.isFallbackEnabled();
        details.put("fallbackEnabled", fallback);
        details.put("normalize", svc.isNormalizeEnabled());
        details.put("executionProvider", svc.getExecutionProvider());
        details.put("maxSeqLen", svc.getMaxSeqLen());
        details.put("inputs", svc.getInputNames());
        details.put("outputs", svc.getOutputNames());
        // perform simple validation on similarity scores
        Map<String, Object> validation = new HashMap<>();
        try {
            double sSame = svc.scorePair("health-check", "health-check");
            double sDiff = svc.scorePair("health-check", "different-text");
            validation.put("score.same", sSame);
            validation.put("score.diff", sDiff);
            validation.put("finite", Double.isFinite(sSame) && Double.isFinite(sDiff));
            validation.put("monotonic", sSame >= sDiff);
            boolean inRange = sSame >= 0.0 && sSame <= 1.0 && sDiff >= 0.0 && sDiff <= 1.0;
            validation.put("inRange", inRange);
        } catch (Throwable t) {
            validation.put("error", t.toString());
        }
        details.put("validation", validation);
        // determine overall health status
        Health.Builder builder;
        if (avail) {
            builder = Health.up();
        } else if (!avail && fallback) {
            builder = Health.status("DEGRADED");
        } else {
            builder = Health.down();
        }
        return builder.withDetails(details).build();
    }
}