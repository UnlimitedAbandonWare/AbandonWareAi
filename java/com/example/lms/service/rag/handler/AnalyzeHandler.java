package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



public class AnalyzeHandler extends AbstractRetrievalHandler {

    

    private static final Logger log = LoggerFactory.getLogger(AnalyzeHandler.class);
    private final AnalyzeWebSearchRetriever retriever;

    public AnalyzeHandler(AnalyzeWebSearchRetriever retriever) {
        this.retriever = retriever;
    }

@Override protected boolean doHandle(Query q, List<Content> acc) {
        try { acc.addAll(retriever.retrieve(q)); }
        catch (Exception e) { log.warn("[Analyze] 실패 - 패스", e); }
        return true;
    }
}