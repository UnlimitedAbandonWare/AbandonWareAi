package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import lombok.RequiredArgsConstructor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import lombok.extern.slf4j.Slf4j;




@Slf4j @RequiredArgsConstructor
public class SelfAskHandler extends AbstractRetrievalHandler {
    private final SelfAskWebSearchRetriever retriever;
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            acc.addAll(retriever.retrieve(q));
            return true;                    // 다음 핸들러도 시도
        } catch (Exception e) {
            log.warn("[SelfAsk] 실패 - 패스", e);
            return true;
        }
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        var md = original.metadata() != null
            ? original.metadata()
            : dev.langchain4j.data.document.Metadata.from(
                java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey));
        // Directly construct a new Query with the updated metadata.  LangChain4j 1.0.x
        // provides a public constructor for Query that accepts text and metadata, rendering the
        // previous builder and reflective fallback unnecessary.
        return new dev.langchain4j.rag.query.Query(original.text(), md);
    }

}