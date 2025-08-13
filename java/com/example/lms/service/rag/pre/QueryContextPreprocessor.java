// src/main/java/com/example/lms/service/rag/pre/QueryContextPreprocessor.java
package com.example.lms.service.rag.pre;

import java.util.Locale;
import java.util.Set;

/**
 * 검색 전 쿼리를 고유명사 추출 및 지역/도메인 맥락 주입을 통해 강화(enrich)하는 전처리기.
 * - enrich(String): 필수 계약
 * - 나머지 메서드는 선택 계약(기본 구현 제공)으로, 구현체에서 필요 시 override 합니다.
 */
public interface QueryContextPreprocessor {

    /**
     * 사용자가 입력한 원본 쿼리를 받아 고유명사 보존, 위치/도메인 맥락 주입, 정규화 등을 적용합니다.
     *
     * @param original 사용자가 입력한 원본 쿼리
     * @return 강화된 쿼리 문자열
     */
    String enrich(String original);

    /**
     * 쿼리에서 특정 도메인을 감지합니다. (기본값: "GENERAL")
     * 예: "GENSHIN", "STARRAIL", "GENERAL"
     *
     * @param q 원본 쿼리
     * @return 감지된 도메인 문자열
     */
    default String detectDomain(String q) {
        return "GENERAL";
    }

    /**
     * 쿼리에서 사용자의 의도를 추정합니다. (기본값: "GENERAL")
     * 예: "RECOMMENDATION", "PAIRING", "GENERAL"
     *
     * @param q 원본 쿼리
     * @return 추정된 의도 문자열
     */
    default String inferIntent(String q) {
        if (q == null) return "GENERAL";
        String s = q.toLowerCase(Locale.ROOT);
        // "추천", "조합", "파티", "시너지" 등과 같은 키워드가 포함되면 '추천' 의도로 파악
        if (s.matches(".*(추천|추천해|추천좀|조합|파티|상성|시너지|픽|티어|메타|어울리(?:는|다)?|궁합).*"))
            return "RECOMMENDATION";
        return "GENERAL";
    }

    /**
     * 특정 도메인이나 의도에 따라 검색 결과에 포함되어야 할 허용 요소를 반환합니다.
     *
     * @param q 원본 쿼리
     * @return 허용 요소 집합 (기본값: 비어 있음)
     */
    default Set<String> allowedElements(String q) {
        return Set.of();
    }

    /**
     * 특정 도메인이나 의도에 따라 검색 결과에서 가급적 제외해야 할 비선호 요소를 반환합니다.
     *
     * @param q 원본 쿼리
     * @return 비선호 요소 집합 (기본값: 비어 있음)
     */
    default Set<String> discouragedElements(String q) {
        return Set.of();
    }
}