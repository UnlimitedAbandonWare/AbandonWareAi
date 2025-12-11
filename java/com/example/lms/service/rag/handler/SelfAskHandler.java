package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SelfAskHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(SelfAskHandler.class);

    private final SelfAskWebSearchRetriever retriever;

    public SelfAskHandler(SelfAskWebSearchRetriever retriever) {
        this.retriever = retriever;
    }


    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            acc.addAll(retriever.retrieve(q));
            // 항상 다음 핸들러도 시도한다.
            return true;
        } catch (Exception e) {
            log.warn("[SelfAsk] 실패 - 패스", e);
            // 실패해도 체인은 계속 진행된다.
            return true;
        }
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(
            dev.langchain4j.rag.query.Query original,
            String sessionKey
    ) {
        var md = original.metadata() != null
                ? original.metadata()
                : dev.langchain4j.data.document.Metadata.from(
                        java.util.Map.of(
                                com.example.lms.service.rag.LangChainRAGService.META_SID,
                                sessionKey
                        ));
        // LangChain4j 1.0.x 에서는 (text, metadata)를 받는 public 생성자를 제공하므로,
        // 빌더/리플렉션 없이 직접 새 Query를 만들어 사용할 수 있다.
        return new dev.langchain4j.rag.query.Query(original.text(), md);
    }
}