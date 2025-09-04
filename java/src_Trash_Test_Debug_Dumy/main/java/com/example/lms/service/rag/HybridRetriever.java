package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.Map;

/**
 * 하이브리드(웹+벡터 등) 검색 계약.
 */
public interface HybridRetriever {

    List<Content> retrieveAll(List<String> queries, int topK);

    List<Content> retrieveProgressive(String query, String sessionId, int topK, Map<String, Object> meta);

    default List<Content> retrieve(Query q) {
        String text = (q == null) ? "" : String.valueOf(q.text());
        return retrieveProgressive(text, null, 10, java.util.Map.of());
    }
}
