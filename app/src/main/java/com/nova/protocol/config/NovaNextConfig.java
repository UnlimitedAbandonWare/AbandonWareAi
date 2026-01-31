package com.nova.protocol.config;

import com.nova.protocol.alloc.RiskKAllocator;
import com.nova.protocol.fusion.CvarAggregator;
import com.nova.protocol.fusion.TailWeightedPowerMeanFuser;
import com.nova.protocol.properties.NovaNextProperties;
import com.nova.protocol.whiten.LegacyLowRankWhiteningAdapter;
import com.nova.protocol.whiten.Whitening;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(NovaNextProperties.class)
@Profile("novanext")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.config.NovaNextConfig
 * Role: config
 * Dependencies: com.nova.protocol.alloc.RiskKAllocator, com.nova.protocol.fusion.CvarAggregator, com.nova.protocol.fusion.TailWeightedPowerMeanFuser, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.config.NovaNextConfig
role: config
*/
public class NovaNextConfig {

    @Bean @Lazy public TailWeightedPowerMeanFuser twpm(){ return new TailWeightedPowerMeanFuser(); }
    @Bean @Lazy public CvarAggregator cvar(){ return new CvarAggregator(); }
    @Bean @Lazy public RiskKAllocator riskKAllocator(){ return new RiskKAllocator(); }

    @Bean @Lazy
    @ConditionalOnProperty(name="whitening.enabled", havingValue="true", matchIfMissing=false)
    public Whitening whitening(){ return new LegacyLowRankWhiteningAdapter(); }
}