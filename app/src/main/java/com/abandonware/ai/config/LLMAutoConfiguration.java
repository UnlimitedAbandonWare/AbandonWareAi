package com.abandonware.ai.config;

import com.abandonware.ai.service.ai.DjlLocalLLMService;
import com.abandonware.ai.service.ai.LocalLLMService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LLMProperties.class)
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.LLMAutoConfiguration
 * Role: config
 * Dependencies: com.abandonware.ai.service.ai.DjlLocalLLMService, com.abandonware.ai.service.ai.LocalLLMService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.config.LLMAutoConfiguration
role: config
*/
public class LLMAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalLLMService.class)
    public LocalLLMService defaultLocalLLMService(LLMProperties props) {
        // DJL default; Jlama bean becomes active when llm.engine=jlama
        return new DjlLocalLLMService(props);
    }
}