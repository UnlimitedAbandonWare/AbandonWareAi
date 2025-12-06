package com.example.lms.config;

/**
 * Simple feature flag container for BM25 retriever.
 */
public class Bm25Config {
    public boolean enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    public String indexPath = System.getProperty("bm25.index.path", "");
    public int topK = Integer.parseInt(System.getProperty("bm25.topK", "50"));
}