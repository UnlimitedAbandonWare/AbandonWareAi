package com.example.lms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.service.soak.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SoakSeedProperties.class)
public class SoakConfig {
    @Bean
    SoakTestService soakTestService(@Qualifier("combinedSoakQueryProvider") SoakQueryProvider provider,
                                    SearchOrchestrator orchestrator,
                                    ObjectProvider<SoakDatasetIngestService> datasetIngest,
                                    ObjectProvider<SoakQuickJsonlExporter> jsonlExporter) {
        return new DefaultSoakTestService(provider, orchestrator,
                datasetIngest.getIfAvailable(),
                jsonlExporter.getIfAvailable());
    }

    /** Fallback orchestrator: returns empty results when none is provided by the app. */
    @Bean
    @ConditionalOnMissingBean(SearchOrchestrator.class)
    SearchOrchestrator searchOrchestrator() {
        return (query, k) -> java.util.Collections.emptyList();
    }
}