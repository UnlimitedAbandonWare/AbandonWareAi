// src/main/java/com/example/lms/search/SmartQueryPlanner.java
package com.example.lms.search;

import com.example.lms.transform.QueryTransformer;
import com.example.lms.search.QueryHygieneFilter; // ⬅️ [수정] 누락된 import 추가
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 지능형 다중 쿼리 생성기.
 * QueryTransformer.transformEnhanced() 결과를 받아 위생 처리(Hygiene) 및 상한(Cap) 적용 후 반환합니다.
 */
@Component
public class SmartQueryPlanner {

    private final QueryTransformer transformer;

    /**
     * 의존성 주입을 위한 생성자.
     * 여러 QueryTransformer 빈 중 'queryTransformer'를 명시적으로 주입받습니다.
     * @param transformer 쿼리 변환을 수행할 트랜스포머
     */
    // ⬅️ [수정] 실제 빈 이름인 'queryTransformer'로 변경
    public SmartQueryPlanner(@Qualifier("queryTransformer") QueryTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * 사용자 질문(+선택적 초안)을 바탕으로 검색에 투입할 "핵심 쿼리" 목록을 생성합니다.
     * <ul>
     * <li><b>중앙 집중 생성</b>: 쿼리 생성 로직은 QueryTransformer로 중앙화합니다.</li>
     * <li><b>위생 및 정제</b>: QueryHygieneFilter를 통해 중복 제거, 빈 문자열 필터링, 길이 제한 등을 적용합니다.</li>
     * </ul>
     * @param userPrompt 사용자 원본 질문
     * @param assistantDraft (선택 사항) 모델이 생성한 1차 초안. 쿼리 확장 힌트로 사용될 수 있습니다.
     * @param maxQueries 반환할 최대 쿼리 개수 (1~4개로 제한)
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
        // 쿼리 개수를 1개 이상 4개 이하로 보정
        int cap = Math.max(1, Math.min(4, maxQueries));

        // QueryTransformer를 통해 원시 쿼리 목록 생성
        List<String> raw = transformer.transformEnhanced(
                Objects.toString(userPrompt, ""),
                assistantDraft
        );

        // 위생 필터를 적용하여 최종 쿼리 목록 반환
        return QueryHygieneFilter.sanitize(raw, cap, 0.80);
    }

    /**
     * assistantDraft 없이 최대 2개의 쿼리를 생성하는 편의 메서드입니다.
     * @param userPrompt 사용자 원본 질문
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt) {
        return plan(userPrompt, null, 2);
    }
}