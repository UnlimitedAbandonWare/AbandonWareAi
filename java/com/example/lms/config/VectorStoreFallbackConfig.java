package com.example.lms.config;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;



/**
 * Fail-soft in-memory vector store. This bean becomes active only if no other
 * EmbeddingStore bean exists (e.g., Pinecone, PGVector, etc.).
 */
@Configuration
public class VectorStoreFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFallbackConfig.class);

    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        log.warn("No EmbeddingStore bean configured; using in-memory fallback (non-persistent).");
        return new InMemoryEmbeddingStore<>();
    }
}