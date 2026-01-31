package com.abandonware.ai.service.rag.whiten;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagWhiteningConfig {

    @Value("${rag.whiten.enabled:false}")
    private boolean enabled;

    @Bean
    public Whitening whitening(){
        if (!enabled) return new IdentityWhitening();
        return new LowRankZcaWhitening();
    }
}