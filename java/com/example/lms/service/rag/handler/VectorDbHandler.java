package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class VectorDbHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(VectorDbHandler.class);

    private final LangChainRAGService ragSvc;
    private final String indexName;
    private final OrchestrationGate gate;

    public VectorDbHandler(LangChainRAGService ragSvc, String indexName, OrchestrationGate gate) {
        this.ragSvc = ragSvc;
        this.indexName = indexName;
        this.gate = gate;
    }

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            if (gate != null && !gate.allowVector(q)) {
                log.debug("[VectorDB] skipped by orchestration gate");
                return true;
            }

            // Guard: skip vector retrieval when the index name is blank or null.
            if (indexName == null || indexName.isBlank()) {
                log.debug("[VectorDB] indexName is blank - skipping vector retrieval (fail-soft).");
                return true;
            }

            ContentRetriever pine = ragSvc.asContentRetriever(indexName);
            if (pine == null) {
                log.debug("[VectorDB] retriever is null - skipping (fail-soft).");
                return true;
            }

            acc.addAll(pine.retrieve(q));
        } catch (Exception e) {
            // fail-soft: log the error but allow the chain to continue
            log.warn("[VectorDB] 실패 - fail-soft", e);
            return true;
        }

        // Always continue to the next handler by default.
        // This ensures subsequent handlers (e.g., WebSearch) are executed.
        log.debug("[VectorDB] fast-path disabled - always continuing to subsequent handlers");
        return true;
    }
}
