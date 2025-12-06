package com.example.lms.service.rag;

import java.util.ArrayList;
import java.util.List;

import com.example.lms.service.rag.model.ContextSlice;

/**
 * Lightweight web retriever for java_clean build. It does NOT call external APIs.
 * Instead, it returns deterministic mock ContextSlice items derived from the query tokens.
 * This avoids "empty list" wash and keeps the pipeline testable without network calls.
 */
public class AnalyzeWebSearchRetriever {

    public List<ContextSlice> search(String query, int topK) {
        if (query == null) query = "";
        String q = query.trim();
        List<ContextSlice> list = new ArrayList<>();
        int k = Math.max(0, Math.min(topK <= 0 ? 5 : topK, 10));
        for (int i = 1; i <= k; i++) {
            String id = "mock:web:" + q + "#" + i;
            String title = "Result " + i + " for \"" + q + "\"";
            String snippet = "Deterministic placeholder result to keep build-path functional.";
            String source = "https://example.com/search?q=" + q + "&rank=" + i;
            double score = 1.0 / (i + 1);
            list.add(new ContextSlice(id, title, snippet, source, score, i));
        }
        return list;
    }
}