package com.example.lms.config;

import com.example.lms.telemetry.MatrixTelemetryExtractor;
import com.example.lms.telemetry.VirtualPointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AutoWiringConfig
 * - Registers beans for telemetry overlays.
 * - Pure Spring config, no external deps. All beans are behind feature flags.
 *
 * Properties (default false):
 *   features.telemetry.virtual-point.enabled
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.context.ApplicationContext")
public class AutoWiringConfig {

    // --- Telemetry beans ---
    @Bean
    @ConditionalOnProperty(name = "features.telemetry.virtual-point.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public MatrixTelemetryExtractor matrixTelemetryExtractor() {
        return new MatrixTelemetryExtractor();
    }

    @Bean
    @ConditionalOnProperty(name = "features.telemetry.virtual-point.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public VirtualPointService virtualPointService() {
        return new VirtualPointService();
    }

}
