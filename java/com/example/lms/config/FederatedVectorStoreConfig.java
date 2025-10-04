
package com.example.lms.config;

import com.example.lms.vector.FederatedEmbeddingStore;
import com.example.lms.vector.TopicRoutingSettings;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration
public class FederatedVectorStoreConfig {

    @Bean
    public UpstashVectorStoreAdapter upstashVectorStoreAdapter(WebClient.Builder builder) {
        return new UpstashVectorStoreAdapter(builder.build());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "retrieval.vector.enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingStore<TextSegment> federatedEmbeddingStore(
            @Qualifier("pineconeEmbeddingStore") EmbeddingStore<TextSegment> primaryStore,
            UpstashVectorStoreAdapter upstash,
            TopicRoutingSettings routing) {
        return new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("primary", primaryStore),
                        new FederatedEmbeddingStore.NamedStore("upstash", upstash)
                ),
                routing
        );
    }
}
