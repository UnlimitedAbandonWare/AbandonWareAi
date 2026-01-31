package com.example.lms.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;



/**
 * Reports the overall health of the model routing subsystem.  This
 * indicator currently returns {@code UP} unconditionally to signal
 * that routing is a lightweight, in-memory decision and does not
 * maintain any external connections.  Additional checks could be
 * incorporated here in the future (e.g., verifying that the high-tier
 * model is configured correctly).
 */
@Component
public class RouterHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up().withDetail("router", "UP").build();
    }
}