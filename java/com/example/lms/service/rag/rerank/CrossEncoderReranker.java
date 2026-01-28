package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;
import java.util.Map;
import java.util.Set;



/**
 * RAG 검색 결과의 순서를 재조정하는 CrossEncoder 재랭커의 표준 인터페이스를 정의합니다.
 * <p>
 * 모든 재랭커 구현체는 이 인터페이스를 따라야 합니다.
 * 이를 통해 향후 다른 재랭킹 모델로 쉽게 교체할 수 있습니다.
 * </p>
 */
public interface CrossEncoderReranker {

    /**
     * 주어진 질의(query)와 후보 콘텐츠 목록을 받아 상위 N개를 재정렬하여 반환합니다.
     *
     * @param query 질의 문자열
     * @param candidates 재랭킹 대상 후보 목록
     * @param topN 반환할 상위 개수
     * @return 재정렬된 상위 N개의 {@link Content} 목록
     */
    List<Content> rerank(String query, List<Content> candidates, int topN);

    /**
     * 편의를 위한 오버로드 메서드: 특정 상호작용 규칙을 받을 수 있도록 확장성을 열어둡니다.
     * <p>기본 구현은 이 규칙을 무시하고 3-인자 rerank 메서드에 위임합니다.</p>
     *
     * @param interactionRules 특정 항목 간의 관계 규칙 (예: A와 B는 반드시 포함)
     * @return 재정렬된 상위 N개의 {@link Content} 목록
     */
    default List<Content> rerank(String query,
                                 List<Content> candidates,
                                 int topN,
                                 Map<String, Set<String>> interactionRules) {
        // 기본 구현에서는 interactionRules를 사용하지 않고 위임합니다.
        // 특정 규칙이 필요한 구현체에서 이 메서드를 오버라이드할 수 있습니다.
        return rerank(query, candidates, topN);
    }
}