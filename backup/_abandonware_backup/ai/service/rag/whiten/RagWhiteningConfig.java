package com.abandonware.ai.service.rag.whiten;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.whiten.RagWhiteningConfig
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.whiten.RagWhiteningConfig
role: config
*/
public class RagWhiteningConfig {

    @Value("${rag.whiten.enabled:false}")
    private boolean enabled;

    @Bean
    public Whitening whitening(){
        if (!enabled) return new IdentityWhitening();
        return new LowRankZcaWhitening();
    }
}