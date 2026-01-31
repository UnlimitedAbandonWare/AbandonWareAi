// src/main/java/com/example/lms/config/RagLightAdapterConfig.java
package com.example.lms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Bridges component scanning so that beans under selected packages are visible to the main application
 * when the base scan is intentionally narrowed.  This adapter is **disabled by default** to avoid
 * bean name collisions with mirrored packages (e.g., `service.*` vs `com.example.lms.service.*`).
 *
 * Enable explicitly with:
 *   adapter.rag-light.enabled=true
 */
@Configuration
@ConditionalOnProperty(prefix = "adapter.rag-light", name = "enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(
        basePackages = {
                // Prefer the canonical packages under com.example.lms.*
                "com.example.lms.service.rag",
                "com.example.lms.service.tools",
                "com.example.lms.vector",
                "com.example.lms.service.rag.fusion"
        },
        excludeFilters = {
                // NOTE: keep legacy planner out even when adapter is enabled.
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        service.rag.planner.SelfAskPlanner.class
                })
        }
)
public class RagLightAdapterConfig { }