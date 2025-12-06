package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Simple web retrieval handler used in the fixed retrieval chain.
 * <p>
 * 이 핸들러는 {@link WebSearchRetriever} 를 감싸서 웹 스니펫 검색을 수행합니다.
 * 실패해도 예외를 던지지 않고 체인을 계속 진행하도록 설계되어 있습니다.
 */
@RequiredArgsConstructor
public class WebHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(WebHandler.class);

    private final WebSearchRetriever retriever;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            acc.addAll(retriever.retrieve(q));
        } catch (Exception e) {
            // fail-soft: log the error and continue to the next handler
            log.warn("[Web] 실패 - 패스", e);
        }
        // 항상 true 를 반환하여 다음 핸들러가 실행되도록 한다.
        return true;
    }
}
