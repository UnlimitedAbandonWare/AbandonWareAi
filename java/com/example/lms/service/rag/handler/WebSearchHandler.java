package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j @RequiredArgsConstructor
public class WebSearchHandler extends AbstractRetrievalHandler {
    private final WebSearchRetriever retriever;
    @Override protected boolean doHandle(Query q, List<Content> acc) {
        try { acc.addAll(retriever.retrieve(q)); }
        catch (Exception e) { log.warn("[WebSearch] 실패 – 패스", e); }
        return true;
    }
}
