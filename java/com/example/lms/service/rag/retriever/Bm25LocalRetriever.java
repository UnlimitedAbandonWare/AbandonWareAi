package com.example.lms.service.rag.retriever;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight local BM25 retriever stub.
 * This implementation does NOT depend on Lucene to avoid build failures.
 * It returns an empty result unless explicitly populated.
 * Feature is guarded by the retrieval.bm25.enabled flag (default false).
 */
public class Bm25LocalRetriever {

    private boolean enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    private String indexPath = System.getProperty("bm25.index.path", "");
    private int topK = Integer.parseInt(System.getProperty("bm25.topK", "50"));

    /**
     * Search local index. Returns an empty list when disabled or index is missing.
     */
    public WebSearchResult search(String query) {
        List<WebDocument> docs = new java.util.ArrayList<>();
        // In a real implementation, use Lucene over indexPath and populate docs.
        return new WebSearchResult("bm25", docs);
    }

    public boolean isEnabled() { return enabled; }
    public int getTopK() { return topK; }
    public String getIndexPath() { return indexPath; }
}