package com.example.lms.service.rag.impl;

import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

/**
 * Provides no-op beans for the {@link HybridRetriever} and
 * {@link SelfAskWebSearchRetriever} interfaces. Declaring these beans
 * as {@code @Primary} ensures that they are selected by Spring when
 * no other implementations are present. Returning empty lists allows
 * the application to initialise successfully even when external search
 * backends are unavailable.
 */
@Configuration
@ConditionalOnProperty(value = "aw.noop.enabled", havingValue = "true", matchIfMissing = false)
public class DefaultNoopBeans {

    /**
     * A primary bean producing a no-op {@link HybridRetriever}. All
     * retrieval methods return empty lists. The anonymous inner class
     * avoids the need for a separate top-level implementation class.
     *
     * @return a no-op hybrid retriever
     */
    @Bean
    @ConditionalOnMissingBean(HybridRetriever.class)
    public HybridRetriever noopHybridRetriever() {
        return new HybridRetriever() {
            @Override
            public List<Content> retrieveAll(List<String> queries, int topK) {
                return List.of();
            }

            @Override
            public List<Content> retrieveProgressive(String q, String sid, int k, Map<String, Object> m) {
                return List.of();
            }
        };
    }

    /**
     * A primary bean producing a no-op {@link SelfAskWebSearchRetriever}.
     * Invoking the {@code askWeb} method returns an empty list regardless of inputs.
     *
     * @return a no-op self‑ask web search retriever
     */
    @Bean
    @ConditionalOnMissingBean(SelfAskWebSearchRetriever.class)
    public SelfAskWebSearchRetriever noopSelfAskWebSearchRetriever() {
        return (question, topK, meta) -> List.of();
    }
}