package com.example.lms.service.rag.port;

import java.util.List;

/**
 * Abstraction over web search providers.
 * Concrete adapters can wrap Brave, Tavily, etc.
 */
public interface WebSearchPort {

    List<SearchSnippet> searchSnippets(String query, int topK);

    String adapterId();

    record SearchSnippet(
            String url,
            String title,
            String snippet,
            double score
    ) {
    }
}
