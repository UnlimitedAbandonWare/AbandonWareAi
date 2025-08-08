
package com.example.lms.service.rag;

/** 쿼리 복잡도 분류 전략 인터페이스. */
public interface QueryComplexityClassifier {

    /**
     * 입력 쿼리를 SIMPLE / AMBIGUOUS / COMPLEX 중 하나로 분류한다.
     * @param query 사용자 쿼리 (null 허용)
     * @return 분류 레벨
     */
    QueryComplexityGate.Level classify(String query);
}
