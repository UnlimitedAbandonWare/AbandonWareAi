package com.abandonware.ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "retrieval.bm25")
public class Bm25Props {
    private boolean enabled = false;
    private String indexPath = "data/lucene";
    private int topK = 20;
    private String analyzer = "nori";
    private int minSnippetChars = 160;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String indexPath) { this.indexPath = indexPath; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public String getAnalyzer() { return analyzer; }
    public void setAnalyzer(String analyzer) { this.analyzer = analyzer; }
    public int getMinSnippetChars() { return minSnippetChars; }
    public void setMinSnippetChars(int minSnippetChars) { this.minSnippetChars = minSnippetChars; }
}