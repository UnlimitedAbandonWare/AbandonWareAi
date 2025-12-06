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
 * 미리 정의된 구체적인 검색어(예: "\"K8 Plus\" 미니PC")로 완전히 대체합니다.
 * 이 방식은 특히 의미가 중복되는 검색어에 효과적입니다.</li>
 * <li><b>부분 문자열 정규화 (Substring Normalization):</b>
 * 내부적으로 보유한 규칙 맵을 이용해 특정 부분 문자열을 표준화합니다.
 * 예: "win11" → "Windows 11", "i9" → "Core i9" 등.</li>
 * </ol>
 */
public final class ProductAliasNormalizer {

    private static final Map<String, String> FULL_QUERY_ALIASES;
    private static final Map<String, String> SUBSTRING_ALIASES;

    static {
        Map<String, String> fq = new LinkedHashMap<>();
        fq.put("k8 plus", "\"K8 Plus\" 미니PC");
        fq.put("win11 pro", "Windows 11 Pro");
        fq.put("win11", "Windows 11");
        FULL_QUERY_ALIASES = Collections.unmodifiableMap(fq);

        Map<String, String> sub = new LinkedHashMap<>();
        sub.put("(?i)\\bwin11\\b", "Windows 11");
        sub.put("(?i)\\bwin10\\b", "Windows 10");
        sub.put("(?i)\\bintel\\s*i9\\b", "Intel Core i9");
        sub.put("(?i)\\bi9\\b", "Core i9");
        sub.put("(?i)\\bi7\\b", "Core i7");
        sub.put("(?i)\\bryzen\\b", "Ryzen"); // 오타 보정
        SUBSTRING_ALIASES = Collections.unmodifiableMap(sub);
    }

    private ProductAliasNormalizer() {}

    /** 주어진 입력을 정규화하여 반환합니다. */
    public static String normalize(String input) {
        if (input == null) return "";
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