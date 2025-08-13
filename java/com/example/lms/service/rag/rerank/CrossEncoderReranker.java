package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical API:
 * - 구현 필수: (query, candidates, topN)
 * - 편의 default: (query, candidates) / (query, candidates, topN, interactionRules)
 */
public interface CrossEncoderReranker {
    /** 구현체는 상위 topN까지 랭크해서 반환해야 합니다. */
    List<Content> rerank(String query, List<Content> candidates, int topN);

    /** 편의 오버로드: 전체를 랭크 후 그대로 반환 */
    default List<Content> rerank(String query, List<Content> candidates) {
        int n = (candidates == null) ? 0 : candidates.size();
        return rerank(query, candidates, n);
    }

    /** 편의 오버로드: 관계 규칙을 받지만 기본 구현은 3-인자로 위임 */
    default List<Content> rerank(String query,
                                 List<Content> candidates,
                                 int topN,
                                 Map<String, Set<String>> interactionRules) {
        return rerank(query, candidates, topN);
    }
}