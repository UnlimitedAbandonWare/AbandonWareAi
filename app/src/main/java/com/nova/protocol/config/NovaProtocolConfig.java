package com.nova.protocol.config;

import com.nova.protocol.guard.AutorunPreflightGate;
import com.nova.protocol.guard.CitationGate;
import com.nova.protocol.guard.PIISanitizer;
import com.nova.protocol.plan.PlanApplier;
import com.nova.protocol.plan.PlanLoader;
import com.nova.protocol.strategy.KAllocationPolicy;
import com.nova.protocol.telemetry.ModeAuditLogger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
@EnableConfigurationProperties(NovaProperties.class)
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.config.NovaProtocolConfig
 * Role: config
 * Feature Flags: telemetry
 * Dependencies: com.nova.protocol.guard.AutorunPreflightGate, com.nova.protocol.guard.CitationGate, com.nova.protocol.guard.PIISanitizer, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.config.NovaProtocolConfig
role: config
flags: [telemetry]
*/
public class NovaProtocolConfig {

    @Bean
    @ConditionalOnMissingBean
    public PlanLoader planLoader() {
        return new PlanLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanApplier planApplier(PlanLoader loader, NovaProperties props) {
        return new PlanApplier(loader, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public CitationGate citationGate(NovaProperties props) {
        return new CitationGate(props.getCitationMin());
    }

    @Bean
    @ConditionalOnMissingBean
    public PIISanitizer piiSanitizer() {
        return new PIISanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public KAllocationPolicy kAllocationPolicy() {
        return new KAllocationPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModeAuditLogger modeAuditLogger(NovaProperties props) {
        return new ModeAuditLogger(props.isModeAuditEnabled());
    }

    @Bean
    public AutorunPreflightGate autorunPreflightGate() {
        return new AutorunPreflightGate();
    }
}