package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import java.util.List;

@Slf4j @RequiredArgsConstructor
public class SelfAskHandler extends AbstractRetrievalHandler {
    private final SelfAskWebSearchRetriever retriever;
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            acc.addAll(retriever.retrieve(q));
            return true;                    // 다음 핸들러도 시도
        } catch (Exception e) {
            log.warn("[SelfAsk] 실패 – 패스", e);
            return true;
        }
    }
}
