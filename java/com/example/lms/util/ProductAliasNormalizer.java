package com.example.lms.util;

import java.util.Map;

/**
 * 미니 PC 제품명과 같이 모호한 검색어를 정확하게 만들어 주는 정규화 도우미입니다.
 *
 * "K8 Plus" 미니 PC는 자동차 K8과 이름이 겹치므로, 여러 변형을 정규화하여
 * 검색 정확도를 높입니다. 따옴표를 통해 구문 검색을 사용하면 입력한 단어/문구가
 * 그대로 포함된 페이지만 반환할 수 있습니다:contentReference[oaicite:2]{index=2}.
 */
public final class ProductAliasNormalizer {
    /**
     * 소문자 키와 정규화된 값 매핑. 값을 따옴표로 감싸고 "미니PC"를 덧붙여
     * 검색 엔진이 자동차 관련 정보를 무시하도록 유도합니다.
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
     * @param query 사용자의 원본 질의
     * @return 정규화된 질의 문자열
     */
    public static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim().toLowerCase();
        return ALIAS.getOrDefault(trimmed, query);
    }
}
