package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface CrossEncoderReranker {
    List<Content> rerank(String query, List<Content> candidates, int topN);

    default List<Content> rerank(
            String query,
            List<Content> candidates,
            int topN,
            Map<String, Set<String>> interactionRules) {
        return rerank(query, candidates, topN);
    }
}
