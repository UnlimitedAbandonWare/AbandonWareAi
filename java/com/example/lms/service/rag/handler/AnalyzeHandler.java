package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j @RequiredArgsConstructor
public class AnalyzeHandler extends AbstractRetrievalHandler {
    private final AnalyzeWebSearchRetriever retriever;
    @Override protected boolean doHandle(Query q, List<Content> acc) {
        try { acc.addAll(retriever.retrieve(q)); }
        catch (Exception e) { log.warn("[Analyze] 실패 – 패스", e); }
        return true;
    }
}
