package com.example.lms.config;

import com.example.lms.service.rag.retriever.bm25.Bm25Retriever;
import com.example.lms.service.rag.retriever.bm25.LuceneBm25Retriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(Bm25Properties.class)
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.config.Bm25Config
 * Role: config
 * Dependencies: com.example.lms.service.rag.retriever.bm25.Bm25Retriever, com.example.lms.service.rag.retriever.bm25.LuceneBm25Retriever
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.config.Bm25Config
role: config
*/
public class Bm25Config {

    @Bean
    @ConditionalOnProperty(prefix = "retrieval.bm25", name = "enabled", havingValue = "true")
    public Bm25Retriever bm25Retriever(Bm25Properties props) {
        if (!"lucene".equalsIgnoreCase(props.getProvider())) {
            // Future: other providers can be plugged in here
            throw new IllegalArgumentException("Unsupported BM25 provider: " + props.getProvider());
        }
        return new LuceneBm25Retriever(Path.of(props.getIndexPath()), "content", props.getTopK());
    }
}

@ConfigurationProperties(prefix = "retrieval.bm25")
class Bm25Properties {
    private boolean enabled = false;
    private String provider = "lucene";
    private String indexPath = "data/lucene/index";
    private int topK = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String indexPath) { this.indexPath = indexPath; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
}