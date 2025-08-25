package com.example.lms.health;

import com.example.lms.service.onnx.OnnxRuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
            return Health.unknown().withDetail("reranker.onnx", "NOT_CONFIGURED").build();
        }
        try {
            if (!svc.available()) {
                return Health.down().withDetail("reranker.onnx", "DOWN").build();
            }
            double s = svc.scorePair("health-check", "health-check");
            return Double.isFinite(s)
                    ? Health.up().withDetail("reranker.onnx", "UP").build()
                    : Health.down().withDetail("reranker.onnx", "DEGRADED").build();
        } catch (Throwable t) {
            return Health.down().withDetail("reranker.onnx", "DOWN").build();
        }
    }
}
