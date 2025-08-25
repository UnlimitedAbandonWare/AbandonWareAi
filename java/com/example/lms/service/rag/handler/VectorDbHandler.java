package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j @RequiredArgsConstructor
public class VectorDbHandler extends AbstractRetrievalHandler {
    private final LangChainRAGService ragSvc;
    private final String indexName;
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            // Guard: skip vector retrieval when the index name is blank or null.
            if (indexName == null || indexName.isBlank()) {
                log.debug("[VectorDB] indexName is blank — skipping vector retrieval (fail-soft).");
                return true;
            }
            ContentRetriever pine = ragSvc.asContentRetriever(indexName);
            if (pine == null) {
                log.debug("[VectorDB] retriever is null — skipping (fail-soft).");
                return true;
            }
            acc.addAll(pine.retrieve(q));
        } catch (Exception e) {
            // fail-soft: log the error but allow the chain to continue
            log.warn("[VectorDB] 실패 – fail-soft", e);
            return true;
        }
        // Always continue to the next handler.
        return true;
    }
}
