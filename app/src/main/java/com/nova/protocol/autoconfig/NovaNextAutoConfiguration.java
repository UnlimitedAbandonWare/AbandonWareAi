package com.nova.protocol.autoconfig;

import com.nova.protocol.properties.NovaNextProperties;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.fusion.TailWeightedPowerMeanFuser;
import com.nova.protocol.fusion.CvarAggregator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NovaNextProperties.class)
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.autoconfig.NovaNextAutoConfiguration
 * Role: config
 * Dependencies: com.nova.protocol.properties.NovaNextProperties, com.nova.protocol.fusion.NovaNextFusionService, com.nova.protocol.fusion.TailWeightedPowerMeanFuser, +1 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.autoconfig.NovaNextAutoConfiguration
role: config
*/
public class NovaNextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TailWeightedPowerMeanFuser tailWeightedPowerMeanFuser() {
        return new TailWeightedPowerMeanFuser();
    }

    @Bean
    @ConditionalOnMissingBean
    public CvarAggregator cvarAggregator() {
        return new CvarAggregator();
    }

    @Bean
    @ConditionalOnMissingBean
    public NovaNextFusionService novaNextFusionService(
            TailWeightedPowerMeanFuser twpm,
            CvarAggregator cvar,
            NovaNextProperties props) {
        return new NovaNextFusionService(twpm, cvar, props);
    }
}