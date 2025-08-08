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
    @Override protected boolean doHandle(Query q, List<Content> acc) {
        try {
            ContentRetriever pine = ragSvc.asContentRetriever(indexName);
            acc.addAll(pine.retrieve(q));
        } catch (Exception e) {
            log.warn("[VectorDB] 실패 – 체인 종료", e);
            return false;            // 에러 땐 중단
        }
        return false;                // 마지막 핸들러
    }
}
