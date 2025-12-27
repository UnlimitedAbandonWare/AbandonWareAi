package com.example.lms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Safe stub: previously caused duplicate bean registration or import syntax errors.
 * This configuration is OFF by default and contains no @Bean methods.
 * Enable only if you really need to override the component-driven FederatedEmbeddingStore.
 */
@Configuration
@ConditionalOnProperty(name = "lms.federatedVectorConfig.enabled", havingValue = "true", matchIfMissing = false)
public class FederatedVectorStoreConfig {
    // intentionally empty
}
