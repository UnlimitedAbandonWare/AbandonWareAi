package com.example.lms.config;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;



/**
 * Health indicator that reflects the availability of the configured vector store.
 * <p>
 * If the application has fallen back to an {@link InMemoryEmbeddingStore} due to a
 * remote store initialization failure, this indicator will report {@code DOWN}
 * and include a diagnostic message. Otherwise, for non-inmemory stores we
 * conservatively report {@code UP} without a destructive no-op.
 */
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final EmbeddingStore<?> store;

    public VectorStoreHealthIndicator(EmbeddingStore<?> store) {
        this.store = store;
    }

    @Override
    public Health health() {
        if (store instanceof InMemoryEmbeddingStore) {
            return Health.down().withDetail("vectorStore", "fallback-inmemory").build();
        }
        return Health.up()
                .withDetail("vectorStore", store.getClass().getSimpleName())
                .build();
    }
}