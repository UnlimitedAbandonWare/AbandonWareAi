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
    public AutorunPreflightGate autorunPreflightGate() {
        return new AutorunPreflightGate();
    }

    @Bean
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
}