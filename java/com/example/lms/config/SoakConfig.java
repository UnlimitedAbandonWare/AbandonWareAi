package com.example.lms.config;

import com.example.lms.service.soak.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class SoakConfig {
    @Bean
    SoakQueryProvider soakQueryProvider() { return new DefaultSoakQueryProvider(); }

    @Bean
    SoakTestService soakTestService(@Qualifier("combinedSoakQueryProvider") SoakQueryProvider provider,
                                    SearchOrchestrator orchestrator) {
        return new DefaultSoakTestService(provider, orchestrator);
    }

    /** Fallback orchestrator: returns empty results when none is provided by the app. */
    @Bean
    @ConditionalOnMissingBean(SearchOrchestrator.class)
    SearchOrchestrator searchOrchestrator() {
        return (query, k) -> java.util.Collections.emptyList();
    }
}
