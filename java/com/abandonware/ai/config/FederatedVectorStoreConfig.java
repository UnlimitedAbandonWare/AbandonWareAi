package com.abandonware.ai.config;

import com.abandonware.ai.vector.FederatedEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FederatedVectorStoreConfig {
    @Bean
    public FederatedEmbeddingStore federatedEmbeddingStore() {
        return new FederatedEmbeddingStore();
    }
}
