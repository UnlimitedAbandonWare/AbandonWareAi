package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * PerspectiveAnalyzeRetriever generates perspective‑based queries (positive and negative
 * viewpoints) and retrieves content via AnalyzeWebSearchRetriever.  It complements
 * the SubQueryAnalyzeRetriever for deeper research.
 */
@Component("perspectiveAnalyzeRetriever")
@RequiredArgsConstructor
public class PerspectiveAnalyzeRetriever implements ContentRetriever {

    private final AnalyzeWebSearchRetriever analyzeWebSearchRetriever;

    @Override
    public List<Content> retrieve(Query query) {
        String text = (query != null && query.text() != null) ? query.text() : "";
        List<String> perspectives = java.util.List.of(
                "Positive aspects of: " + text,
                "Negative aspects of: " + text
        );
        return perspectives.parallelStream()
                .map(pq -> analyzeWebSearchRetriever.retrieve(new Query(pq)))
                .flatMap((java.util.List<Content> list) -> list.stream())
                .collect(java.util.stream.Collectors.toList());
    }
}