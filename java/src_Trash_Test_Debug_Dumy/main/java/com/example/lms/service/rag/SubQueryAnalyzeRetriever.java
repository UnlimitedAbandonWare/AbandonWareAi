package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * SubQueryAnalyzeRetriever generates a few focused sub‑queries from the original
 * query and delegates retrieval to the AnalyzeWebSearchRetriever.  It is used
 * by DeepResearchRetriever to collect additional supporting evidence.
 */
@Component("subQueryAnalyzeRetriever")
@RequiredArgsConstructor
public class SubQueryAnalyzeRetriever implements ContentRetriever {

    private final AnalyzeWebSearchRetriever analyzeWebSearchRetriever;

    @Override
    public List<Content> retrieve(Query query) {
        String text = (query != null && query.text() != null) ? query.text() : "";
        List<String> subs = java.util.List.of(
                "Main subject of: " + text,
                "Key aspects of: " + text
        );
        return subs.parallelStream()
                .map(sq -> analyzeWebSearchRetriever.retrieve(new Query(sq)))
                .flatMap((java.util.List<Content> list) -> list.stream())
                .collect(java.util.stream.Collectors.toList());
    }
}