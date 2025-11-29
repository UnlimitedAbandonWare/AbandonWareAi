// src/main/java/com/example/lms/health/OnnxHealthIndicator.java
package com.example.lms.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.example.lms.service.onnx.OnnxRuntimeService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; // ✅ 추가
@ConditionalOnBean(OnnxRuntimeService.class)
@Component
public class OnnxHealthIndicator implements HealthIndicator {

    private final OnnxRuntimeService onnx;

    public OnnxHealthIndicator(OnnxRuntimeService onnx) {
        this.onnx = onnx;
    }

    /**
     * Report the health of the ONNX runtime and reranker in a single indicator.  When
     * no runtime service is available the status is UNKNOWN.  If a runtime is
     * present but no model has been loaded {@link OnnxRuntimeService#available()}
     * will return {@code false} and the health status is DOWN.  When a model
     * is loaded this method also performs a trivial {@code scorePair}
     * invocation to ensure the pipeline returns a finite value.  A non-finite
     * score will downgrade the status to DEGRADED.  Any thrown exception
     * likewise yields a DOWN status.
     */
    @Override
    public Health health() {
        if (onnx == null) {
            return Health.unknown().withDetail("onnx", "NOT_CONFIGURED").build();
        }
        try {
            if (!onnx.available()) {
                return Health.down().withDetail("onnx", "DOWN").build();
            }
            double score = onnx.scorePair("health-check", "health-check");
            if (Double.isFinite(score)) {
                return Health.up().withDetail("onnx", "UP").build();
            } else {
                return Health.down().withDetail("onnx", "DEGRADED").build();
            }
        } catch (Throwable t) {
            return Health.down().withDetail("onnx", "DOWN").build();
        }
    }
}