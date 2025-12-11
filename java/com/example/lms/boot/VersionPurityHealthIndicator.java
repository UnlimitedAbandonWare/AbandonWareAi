// src/main/java/com/example/lms/boot/VersionPurityHealthIndicator.java
package com.example.lms.boot;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;



@Component
public class VersionPurityHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        String cp = System.getProperty("java.class.path", "");
        boolean mixed = cp.matches("(?s).*langchain4j-.*0\\.2\\..*") && cp.matches("(?s).*langchain4j-.*1\\.0\\..*");
        if (mixed) {
            return Health.down().withDetail("langchain4jVersion", "MIXED(0.2.x & 1.0.x)").build();
        }
        return Health.up().withDetail("langchain4jVersion", "OK").build();
    }
}