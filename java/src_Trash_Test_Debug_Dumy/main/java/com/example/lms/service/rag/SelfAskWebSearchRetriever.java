package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Self-Ask 단계에서 웹 검색을 수행하는 어댑터 인터페이스(단일 원천).
 * 기존 구현을 최대한 건드리지 않기 위해 askWeb([TODO])을 기본으로 두고,
 * 핸들러에서 쓰기 좋은 retrieve(Query) default 메서드를 제공합니다.
 */
public interface SelfAskWebSearchRetriever {

    /**
     * 질의와 메타데이터를 기반으로 웹 검색을 수행합니다.
     *
     * @param question 검색할 질문 텍스트 (null이면 빈 문자열)
     * @param topK     상위 몇 개의 결과를 반환할지 지정
     * @param meta     추가 메타데이터. 사용하지 않는 경우 빈 Map 전달 가능
     * @return 검색 결과 컨텐츠 목록
     */
    List<Content> askWeb(String question, int topK, Map<String, Object> meta);

    /**
     * LangChain4j의 Query 객체를 받아 검색을 수행하는 기본 메서드입니다.
     * 질문 텍스트를 추출하여 askWeb([TODO])을 호출하며, topK는 기본값 5를 사용합니다.
     *
     * @param q LangChain4j 쿼리 객체
     * @return 검색 결과 컨텐츠 목록
     */
    default List<Content> retrieve(Query q) {
        String question = (q == null || q.text() == null) ? "" : q.text();
        return askWeb(question, 5, Collections.emptyMap());
    }
}