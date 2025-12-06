package com.example.lms.config;

import com.example.lms.alias.TileAliasCorrector;
import com.example.lms.mpc.MpcPreprocessor;
import com.example.lms.mpc.NoopMpcPreprocessor;
import com.example.lms.telemetry.MatrixTelemetryExtractor;
import com.example.lms.telemetry.VirtualPointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AutoWiringConfig
 * - Registers beans for telemetry, alias overlay and MPC preprocessor.
 * - Pure Spring config, no external deps. All beans are behind feature flags.
 *
 * Properties (default false):
 *   features.telemetry.virtual-point.enabled
 *   features.alias.corrector.enabled
 *   features.mpc.enabled
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

    // --- Alias corrector (overlay) ---
    @Bean
    @ConditionalOnProperty(name = "features.alias.corrector.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public TileAliasCorrector tileAliasCorrector() {
        return new TileAliasCorrector();
    }

    // --- MPC preprocessor ---
    @Bean
    @ConditionalOnProperty(name = "features.mpc.enabled", havingValue = "true")
    @ConditionalOnMissingBean(MpcPreprocessor.class)
    public MpcPreprocessor mpcPreprocessor() {
        // default no-op impl; real impl can override via own @Bean
        return new NoopMpcPreprocessor();
    }
}