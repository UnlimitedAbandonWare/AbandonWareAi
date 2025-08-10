package com.example.lms.util;

import java.util.Map;

/**
 * 미니 PC 제품명처럼 모호한 검색어를 정규화해 검색 정확도를 높이는 도우미.
 *
 * 예: 자동차 K8과 혼동되는 "K8 Plus"는 따옴표+미니PC 키워드로 정규화하여
 * 자동차 결과를 배제하도록 유도합니다.
 */
public final class ProductAliasNormalizer {

    /**
     * 소문자 키 → 정규화된 표현 매핑.
     * 값은 따옴표로 감싸고 "미니PC"를 덧붙입니다.
     */
    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("k8plus", "\"K8 Plus\" 미니PC"),
            Map.entry("k8 plus", "\"K8 Plus\" 미니PC"),
            Map.entry("k8+", "\"K8 Plus\" 미니PC"),
            Map.entry("케이8플러스", "\"케이8 플러스\" 미니PC"),
            Map.entry("케이8 플러스", "\"케이8 플러스\" 미니PC")
    );

    private ProductAliasNormalizer() {
        // utility class – no instantiation
    }

    /**
     * 입력 문자열이 알려진 별칭에 해당하면 정규화된 문자열로 변환합니다.
     *
     * @param query 사용자의 원본 질의 (null 허용)
     * @return 정규화된 질의 문자열(매칭 없으면 원본), query가 null이면 null
     */
    public static String normalize(String query) {
        if (query == null) return null;
        String trimmed = query.trim().toLowerCase();
        return ALIAS.getOrDefault(trimmed, query);
    }
}