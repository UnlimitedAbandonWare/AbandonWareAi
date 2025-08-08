package com.example.lms.service.rag.handler;

import java.util.List;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

/** 체인 연결용 베이스 */

public abstract class AbstractRetrievalHandler implements RetrievalHandler {
    private RetrievalHandler next;


    public RetrievalHandler linkWith(RetrievalHandler n) {   // ★ 반환타입 통일
        this.next = n;
        return n;
    }

    @Override public boolean handle(Query q, List<Content> acc) {
        if (doHandle(q, acc) && next != null) {
            return next.handle(q, acc);
        }
        return false;
    }
    protected abstract boolean doHandle(Query q, List<Content> acc);
}
