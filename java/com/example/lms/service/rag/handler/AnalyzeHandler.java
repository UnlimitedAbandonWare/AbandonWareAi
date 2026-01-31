package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AnalyzeHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeHandler.class);

    private final AnalyzeWebSearchRetriever retriever;
    private final OrchestrationGate gate;

    public AnalyzeHandler(AnalyzeWebSearchRetriever retriever, OrchestrationGate gate) {
        this.retriever = retriever;
        this.gate = gate;
    }

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            if (gate != null && !gate.allowAnalyze(q)) {
                log.debug("[Analyze] skipped by orchestration gate");
                return true;
            }

            acc.addAll(retriever.retrieve(q));
            // 항상 다음 핸들러도 시도한다.
            return true;
        } catch (Exception e) {
            log.warn("[Analyze] failed: {}", e.toString());
            return true;
        }
    }
}
