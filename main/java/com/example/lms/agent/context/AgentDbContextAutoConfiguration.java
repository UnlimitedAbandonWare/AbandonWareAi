package com.example.lms.agent.context;

import com.example.lms.repository.RagOpsLedgerRepository;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.strategy.StrategyPerformanceRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AgentDbContextProperties.class)
@ConditionalOnProperty(prefix = "agent.db-context", name = "enabled", havingValue = "true")
public class AgentDbContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentDbContextProvider agentDbContextProvider(TranslationMemoryRepository memoryRepo,
                                                         RagOpsLedgerRepository ledgerRepo,
                                                         StrategyPerformanceRepository strategyRepo,
                                                         AgentDbContextProperties properties) {
        return new AgentDbContextProvider(memoryRepo, ledgerRepo, strategyRepo, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentDbContextController agentDbContextController(AgentDbContextProvider provider) {
        return new AgentDbContextController(provider);
    }
}
