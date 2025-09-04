package com.example.lms.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모호하거나 일관성 없는 제품명, 검색어를 표준화된 형식으로 정규화하는 유틸리티 클래스.
 *
 * <p>이 클래스는 두 단계의 정규화 로직을 수행합니다:</p>
 * <ol>
 * <li><b>전체 질의 정규화 (Full Query Normalization):</b>
 * 사용자의 입력 전체가 알려진 별칭(예: "k8 plus")과 일치할 경우, 검색 정확도를 높이기 위해
 * 미리 정의된 구체적인 검색어(예: ""K8 Plus" 미니PC")로 완전히 대체합니다.
 * 이 방식은 특히 의미가 중복되는 검색어에 효과적입니다.</li>
 * <li><b>부분 문자열 정규화 (Substring Normalization):</b>
 * 전체 질의 대체가 발생하지 않은 경우, 질의 내부에 포함된 다양한 제품명의 별칭이나 오타
 * (예: "arc graphics", "코어 울트라", "i9- 13900")를 정규 표현식을 사용하여 표준 표기법으로 수정합니다.</li>
 * </ol>
 */
public final class ProductAliasNormalizer {

    /**
     * 전체 질의 대체를 위한 맵. 키는 소문자로 정규화된 사용자 입력, 값은 대체될 특정 검색어입니다.
     */
    private static final Map<String, String> FULL_QUERY_ALIASES;

    /**
     * 부분 문자열 대체를 위한 맵. 키는 대소문자를 무시하는 정규 표현식, 값은 표준 제품명입니다.
     */
    private static final Map<String, String> SUBSTRING_ALIASES;

    static {
        // 전체 질의 별칭 초기화 (자동차 K8과 혼동되는 "K8 Plus" 등)
        Map<String, String> fullQueryMap = new LinkedHashMap<>();
        fullQueryMap.put("k8plus", "\"K8 Plus\" 미니PC");
        fullQueryMap.put("k8 plus", "\"K8 Plus\" 미니PC");
        fullQueryMap.put("k8+", "\"K8 Plus\" 미니PC");
        fullQueryMap.put("케이8플러스", "\"케이8 플러스\" 미니PC");
        fullQueryMap.put("케이8 플러스", "\"케이8 플러스\" 미니PC");
        FULL_QUERY_ALIASES = Collections.unmodifiableMap(fullQueryMap);

        // 부분 문자열 별칭 초기화 (일반적인 제품명 변형)
        Map<String, String> substringMap = new LinkedHashMap<>();
        substringMap.put("(?i)arc\\s*graphics", "Arc Graphics");
        substringMap.put("(?i)코어\\s*울트라", "Core Ultra");
        substringMap.put("(?i)ryzen\\s*7\\s*7800x3d", "Ryzen 7 7800X3D");
        substringMap.put("(?i)i9-\\s*13900", "i9-13900"); // 공백 변형 처리
        SUBSTRING_ALIASES = Collections.unmodifiableMap(substringMap);
    }

    private ProductAliasNormalizer() {
        // 유틸리티 클래스이므로 인스턴스화 방지
    }

    /**
     * 입력된 질의 문자열을 정규화합니다.
     * <p>
     * 먼저 전체 질의 대체를 시도하고, 일치하는 항목이 없으면 부분 문자열 정규화를 수행합니다.
     * </p>
     *
     * @param input 사용자의 원본 질의 (null 허용)
     * @return 정규화된 질의 문자열. 입력이 null이거나 비어있으면 원본을 그대로 반환합니다.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // 1단계: 전체 질의 정규화 시도
        String trimmedLower = input.trim().toLowerCase();
        String fullMatch = FULL_QUERY_ALIASES.get(trimmedLower);
        if (fullMatch != null) {
            return fullMatch; // 특정 검색어로 완전히 대체
        }

        // 2단계: 부분 문자열 정규화 수행
        String output = input;
        for (Map.Entry<String, String> entry : SUBSTRING_ALIASES.entrySet()) {
            output = output.replaceAll(entry.getKey(), entry.getValue());
        }
        return output;
    }
}