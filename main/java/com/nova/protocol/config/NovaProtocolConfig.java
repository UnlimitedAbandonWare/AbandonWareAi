package com.nova.protocol.config;

import com.nova.protocol.guard.AutorunPreflightGate;
import com.nova.protocol.guard.CitationGate;
import com.nova.protocol.guard.PIISanitizer;
import com.example.lms.service.rag.fusion.TailWeightedPowerMeanFuser;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.nova.protocol.alloc.RiskKAllocator;
import com.nova.protocol.alloc.SimpleRiskKAllocator;
import com.nova.protocol.fusion.CvarAggregator;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.plan.PlanApplier;
import com.nova.protocol.plan.PlanLoader;
import com.nova.protocol.properties.NovaNextProperties;
import com.nova.protocol.strategy.KAllocationPolicy;
import com.nova.protocol.telemetry.ModeAuditLogger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;



@Configuration
@EnableConfigurationProperties({NovaProperties.class, NovaNextProperties.class})
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

    @Bean(name = "novaProtocolCitationGate")
    @ConditionalOnMissingBean(name = "novaProtocolCitationGate")
    public CitationGate novaProtocolCitationGate(NovaProperties props) {
        return new CitationGate(props.getCitationMin());
    }

    @Bean(name = "novaProtocolPiiSanitizer")
    @ConditionalOnMissingBean(name = "novaProtocolPiiSanitizer")
    public PIISanitizer novaProtocolPiiSanitizer() {
        return new PIISanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public KAllocationPolicy kAllocationPolicy() {
        return new KAllocationPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public RiskKAllocator riskKAllocator() {
        return new SimpleRiskKAllocator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CvarAggregator cvarAggregator() {
        return new CvarAggregator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModeAuditLogger modeAuditLogger(NovaProperties props) {
        return new ModeAuditLogger(props.isModeAuditEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.next", name = "enabled", havingValue = "true")
    public NovaNextFusionService novaNextFusionService(NovaNextProperties props,
                                                       RiskKAllocator riskKAllocator,
                                                       ObjectProvider<DppDiversityReranker> dppReranker,
                                                       ObjectProvider<TailWeightedPowerMeanFuser> twpmFuser,
                                                       CvarAggregator cvarAggregator) {
        return new NovaNextFusionService(
                props,
                riskKAllocator,
                dppReranker == null ? null : dppReranker.getIfAvailable(),
                twpmFuser == null ? null : twpmFuser.getIfAvailable(),
                cvarAggregator);
    }
}
