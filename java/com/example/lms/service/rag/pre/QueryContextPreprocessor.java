package com.example.lms.service.rag.pre;

import java.util.Locale;
import java.util.Set;

/**
 * 검색 전 쿼리를 고유명사 추출 및 지역/도메인 맥락 주입을 통해 강화(enrich)하는 전처리기.
 * - enrich(String): 필수 계약
 * - 나머지 메서드는 선택 계약(기본 구현 제공)으로, 구현체에서 필요 시 override
 */
public interface QueryContextPreprocessor {

    /**
     * @param original 사용자가 입력한 원본 쿼리
     * @return 고유명사 보존·위치/도메인 맥락이 주입된 쿼리(정규화 포함)
     */
    String enrich(String original);

    /** 도메인 감지(기본 GENERAL). 예: "GENSHIN", "GENERAL" 등 */
    default String detectDomain(String q) {
        return "GENERAL";
    }

    /** 의도 추정(기본 GENERAL). 예: "RECOMMENDATION", "GENERAL" */
    default String inferIntent(String q) {
        if (q == null) return "GENERAL";
        String s = q.toLowerCase(Locale.ROOT);
        if (s.matches(".*(추천|조합|파티|상성|시너지|픽|티어|메타).*")) return "RECOMMENDATION";
        return "GENERAL";
    }

    /** 허용 원소(기본 빈 집합) */
    default Set<String> allowedElements(String q) {
        return Set.of();
    }

    /** 비선호 원소(기본 빈 집합) */
    default Set<String> discouragedElements(String q) {
        return Set.of();
    }
}
