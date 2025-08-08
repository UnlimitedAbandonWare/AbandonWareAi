package com.example.lms.service.rag.pre;

/**
 * 검색 전 쿼리를 고유명사 추출 및 지역 맥락 주입을 통해 강화(enrich)하는 전처리기.
 */
public interface QueryContextPreprocessor {

    /**
     * @param original 사용자가 입력한 원본 쿼리
     * @return 고유명사 보존·위치 정보가 주입된 쿼리
     */
    String enrich(String original);
}