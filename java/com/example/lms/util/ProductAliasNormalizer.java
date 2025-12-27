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
// [ADD] Galaxy Z Fold 7 관련
fq.put("fold 7", "Galaxy Z Fold 7");
fq.put("fold7", "Galaxy Z Fold 7");
fq.put("galaxy fold 7", "Galaxy Z Fold 7");
fq.put("갤럭시 z 폴드7", "갤럭시 Z 폴드7");
fq.put("갤럭시 z 폴드 7", "갤럭시 Z 폴드7");
fq.put("폴드7", "갤럭시 Z 폴드7");
fq.put("폴드 7", "갤럭시 Z 폴드7");
// [ADD] Galaxy Z Flip 7
fq.put("flip 7", "Galaxy Z Flip 7");
fq.put("플립7", "갤럭시 Z 플립7");
fq.put("플립 7", "갤럭시 Z 플립7");
// [ADD] Galaxy S25
fq.put("s25", "Galaxy S25");
fq.put("갤럭시 s25", "Galaxy S25");
// [ADD] iPhone 17+
fq.put("아이폰 17", "iPhone 17");
fq.put("아이폰17", "iPhone 17");

        // [EXT] classpath: /product-aliases-full.properties 로부터 추가/오버라이드 허용
        loadFromProperties("/product-aliases-full.properties", fq);

        FULL_QUERY_ALIASES = Collections.unmodifiableMap(fq);

        Map<String, String> sub = new LinkedHashMap<>();
        sub.put("(?i)\\bwin11\\b", "Windows 11");
        sub.put("(?i)\\bwin10\\b", "Windows 10");
        sub.put("(?i)\\bintel\\s*i9\\b", "Intel Core i9");
        sub.put("(?i)\\bi9\\b", "Core i9");
        sub.put("(?i)\\bi7\\b", "Core i7");
        sub.put("(?i)\\bryzen\\b", "Ryzen"); // 오타 보정
// [ADD] Fold/Flip 부분 문자열 정규화
sub.put("(?i)\\bfold\\s*7\\b", "Galaxy Z Fold 7");
sub.put("폴드\\s*7", "갤럭시 Z 폴드7");
sub.put("(?i)\\bflip\\s*7\\b", "Galaxy Z Flip 7");
sub.put("플립\\s*7", "갤럭시 Z 플립7");
// [ADD] S25
sub.put("(?i)\\bs\\s*25\\b", "Galaxy S25");
// [ADD] iPhone 17+
sub.put("(?i)\\biphone\\s*(1[7-9])\\b", "iPhone $1");
sub.put("아이폰\\s*(1[7-9])", "iPhone $1");

                // [EXT] classpath: /product-aliases-substring.properties 로부터 추가/오버라이드 허용
        loadFromProperties("/product-aliases-substring.properties", sub);

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


    /**
     * classpath 상의 properties 파일에서 alias를 읽어 target 맵에 병합한다.
     *
     * - key=value 형식
     * - 동일 key가 이미 있으면 덮어쓰지 않고 유지
     */
    private static void loadFromProperties(String resourcePath, Map<String, String> target) {
        java.io.InputStream in = null;
        try {
            in = ProductAliasNormalizer.class.getResourceAsStream(resourcePath);
            if (in == null) {
                return;
            }
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            for (String name : props.stringPropertyNames()) {
                String key = name == null ? null : name.trim();
                String value = props.getProperty(name);
                if (key == null || key.isEmpty() || value == null) {
                    continue;
                }
                String v = value.trim();
                if (v.isEmpty()) {
                    continue;
                }
                // 기존 값 보존
                target.putIfAbsent(key, v);
            }
        } catch (Exception ignore) {
            // properties 파일이 없거나 파싱 실패해도 기존 하드코딩 alias는 그대로 사용
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignore) {}
            }
        }
    }

}