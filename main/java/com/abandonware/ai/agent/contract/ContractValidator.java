
package com.abandonware.ai.agent.contract;

import com.abandonware.ai.agent.tool.ToolRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class ContractValidator {

    @Bean
    @ConditionalOnMissingBean(name = "manifestValidatorRunner")
    public ApplicationRunner manifestValidatorRunner(ToolRegistry registry, ToolManifestCatalog catalog) {
        return args -> catalog.writeValidationReport(registry);
    }
}
