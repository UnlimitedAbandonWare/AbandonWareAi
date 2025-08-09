package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import java.util.List;

/**
 * 검색 결과 후보 목록을 쿼리와의 관련성을 기준으로 재정렬하는 Reranker의 역할을 정의하는 인터페이스.
 */
public interface CrossEncoderReranker {

    /**
     * 후보 목록(candidates)을 쿼리(query)와의 관련도에 따라 재정렬하고 상위 N개를 반환합니다.
     *
     * @param query      사용자 쿼리
     * @param candidates 재정렬할 후보 Content 목록
     * @param topN       반환할 결과의 수
     * @return 재정렬된 상위 N개의 Content 목록
     */

    List<Content> rerank(String query, List<Content> candidates, int topN);
}