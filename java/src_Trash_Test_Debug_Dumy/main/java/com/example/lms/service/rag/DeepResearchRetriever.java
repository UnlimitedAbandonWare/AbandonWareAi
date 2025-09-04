// src/main/java/com/example/lms/service/rag/DeepResearchRetriever.java
package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component("deepResearchRetriever")
public class DeepResearchRetriever implements ContentRetriever {

    private final ContentRetriever subQuery;
    private final ContentRetriever perspective;

    public DeepResearchRetriever(
            @Qualifier("subQueryAnalyzeRetriever") ContentRetriever subQuery,
            @Qualifier("perspectiveAnalyzeRetriever") ContentRetriever perspective
    ) {
        this.subQuery = subQuery;
        this.perspective = perspective;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> out = new ArrayList<>();
        try { out.addAll(subQuery.retrieve(query)); } catch (Exception ignore) {}
        try { out.addAll(perspective.retrieve(query)); } catch (Exception ignore) {}
        return out;
    }
}
