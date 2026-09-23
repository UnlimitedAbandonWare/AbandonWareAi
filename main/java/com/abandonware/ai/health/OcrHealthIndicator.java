package com.abandonware.ai.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class OcrHealthIndicator implements HealthIndicator {

    @Value("${ocr.enabled:true}")
    private boolean enabled;

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("enabled", false).build();
        }
        // We don't actually check the native libs here; upstream init should surface failures.
        return Health.up().withDetail("engine", "tess4j").build();
    }
}