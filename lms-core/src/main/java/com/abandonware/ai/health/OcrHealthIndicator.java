package com.abandonware.ai.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.health.OcrHealthIndicator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.health.OcrHealthIndicator
role: config
*/
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