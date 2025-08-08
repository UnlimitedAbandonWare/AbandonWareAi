package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;

/** Chain-of-Responsibility 최상위 인터페이스 */
public interface RetrievalHandler {
    /**
     * @param query   원본 쿼리
     * @param acc     누적 컨텐츠(핸들러는 필요한 경우 addAll)
     * @return        true  → 다음 핸들러로 계속 전파
     *                false → 체인 중단
     */

    /** 다음 핸들러를 연결하고, 그 핸들러를 그대로 반환한다. */
    RetrievalHandler linkWith(RetrievalHandler next);

    /** 검색 처리 후 acc 에 결과를 누적한다. */
    /**
     * @return true  → 다음 핸들러로 전파
     *         false → 체인 중단
     */
    boolean handle(Query query, List<Content> acc);
}
