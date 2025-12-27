package com.example.lms.service.rag;


/**
 * 쿼리의 복잡도를 분류하는 전략을 정의하는 인터페이스입니다.
 * <p>
 * 이 인터페이스의 구현체를 Spring Bean으로 등록하면, {@link QueryComplexityGate}가
 * 이를 우선적으로 사용하여 쿼리의 복잡도를 판단합니다.
 * 예를 들어, 정교한 머신러닝(ML) 기반 분류기를 여기에 연결할 수 있습니다.
 */
public interface QueryComplexityClassifier {

    /**
     * 입력된 쿼리의 복잡도를 분석하여 {@code SIMPLE}, {@code AMBIGUOUS}, {@code COMPLEX} 중
     * 하나로 분류합니다.
     *
     * @param query 분류할 사용자 쿼리 문자열 (null이 될 수 있음)
     * @return      분류된 복잡도 레벨 ({@link QueryComplexityGate.Level})
     */
    QueryComplexityGate.Level classify(String query);
}