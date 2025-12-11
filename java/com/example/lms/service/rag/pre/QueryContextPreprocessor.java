// src/main/java/com/example/lms/service/rag/pre/QueryContextPreprocessor.java
package com.example.lms.service.rag.pre;

import java.util.Locale;
import java.util.Set;
import java.util.Map;


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
        // '궁합/시너지/어울림/조합/파티'는 PAIRING으로 분류

        // 현재 적용된 로직 (PAIRING 우선 분리)
        if (s.matches(".*(잘\\s*어울리|어울리(?:는|다)?|궁합|상성|시너지|조합|파티).*"))
            return "PAIRING";
        if (s.matches(".*(추천|추천해|추천좀|픽|티어|메타).*"))
            return "RECOMMENDATION";
        return "GENERAL";
    }

    /** 쿼리 기반 **동적 관계 규칙**을 반환합니다.
     * 예) RELATIONSHIP_CONTAINS → {"공기","하늘"}, RELATIONSHIP_IS_PART_OF → {"자연"} */
    default Map<String, Set<String>> getInteractionRules(String q) { return Map.of(); }
}