package com.example.lms.service.rag.knowledge;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * UniversalContextLexicon
 *
 * <p>텍스트에서 도메인/속성(Attribute)을 간단히 추론하고
 * 해당 속성에 따른 정책(Policy)을 제공하는 경량 Lexicon 레이어입니다.
 *
 * <p>주의:
 * <ul>
 *   <li>현재 매핑 데이터는 샘플 수준의 하드코딩 값입니다.</li>
 *   <li>실제 프로덕션 환경에서는 universal-lexicon.yml 등 외부 설정으로 분리해야 합니다.</li>
 * </ul>
 */
@Component
public class UniversalContextLexicon {

    // TODO: universal-lexicon.yml로 분리 예정
    private static final Map<String, String> ENTITY_TO_ATTRIBUTE = new HashMap<>();
    private static final Map<Pattern, String> ATTR_HINT = new LinkedHashMap<>();

    static {
        // [1] GENSHIN / GAME (기존 GenshinElementLexicon에서 사용하던 캐릭터 일부 이관)
        put("푸리나", "GENSHIN_HYDRO");
        put("furina", "GENSHIN_HYDRO");
        put("다이루크", "GENSHIN_PYRO");
        put("diluc", "GENSHIN_PYRO");
        put("라이덴", "GENSHIN_ELECTRO");
        put("라이덴 쇼군", "GENSHIN_ELECTRO");
        put("raiden shogun", "GENSHIN_ELECTRO");
        put("원신", "GENSHIN_WORLD");
        put("genshin", "GENSHIN_WORLD");

        // [2] TECH / IT
        put("spring", "TECH_JAVA");
        put("springboot", "TECH_JAVA");
        put("스프링부트", "TECH_JAVA");
        put("자바", "TECH_JAVA");
        put("java", "TECH_JAVA");

        put("python", "TECH_PYTHON");
        put("파이썬", "TECH_PYTHON");
        put("pandas", "TECH_PYTHON");
        put("numpy", "TECH_PYTHON");

        put("react", "TECH_JS");
        put("javascript", "TECH_JS");
        put("자바스크립트", "TECH_JS");

        // [3] ELECTRONICS
        put("galaxy", "ELEC_SAMSUNG");
        put("갤럭시", "ELEC_SAMSUNG");
        put("iphone", "ELEC_APPLE");
        put("아이폰", "ELEC_APPLE");
        put("맥북", "ELEC_APPLE");
        put("macbook", "ELEC_APPLE");

        // [4] COOKING (샘플)
        put("레시피", "COOKING_GENERAL");
        put("recipe", "COOKING_GENERAL");

        // [5] HINTS (정규식 기반 패턴)
        hint("(?i)java|자바|jvm", "TECH_JAVA");
        hint("(?i)python|파이썬", "TECH_PYTHON");
        hint("(?i)cryo|얼음|빙결", "GENSHIN_CRYO");
        hint("(?i)pyro|불\\s*원소|화염", "GENSHIN_PYRO");
        hint("(?i)electro|번개|감전", "GENSHIN_ELECTRO");
    }

    private static void put(String k, String v) {
        ENTITY_TO_ATTRIBUTE.put(k.toLowerCase(Locale.ROOT), v);
    }

    private static void hint(String regex, String attr) {
        ATTR_HINT.put(Pattern.compile(regex), attr);
    }

    /**
     * 텍스트에서 가장 강력한 속성 하나를 추론합니다.
     *
     * @param text 자유 형식 텍스트
     * @return Attribute 코드(예: GENSHIN_PYRO, TECH_JAVA) 또는 null
     */
    public String inferAttribute(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.toLowerCase(Locale.ROOT);

        // 1. 힌트 패턴 우선 검사
        for (Map.Entry<Pattern, String> e : ATTR_HINT.entrySet()) {
            if (e.getKey().matcher(s).find()) {
                return e.getValue();
            }
        }

        // 2. 엔티티 매핑 검사
        for (Map.Entry<String, String> e : ENTITY_TO_ATTRIBUTE.entrySet()) {
            if (s.contains(e.getKey())) {
                return e.getValue();
            }
        }

        return null; // 속성 없음 (GENERAL)
    }

    /**
     * 질의 문자열에 대한 도메인 정책을 반환합니다.
     *
     * <p>allowed / discouraged 세트는 "이 속성의 질문에 대해서
     * 어떤 토큰/속성이 허용/지양되는가" 를 나타내는 가벼운 힌트입니다.
     */
    public Policy policyForQuery(String query) {
        String attr = inferAttribute(query);
        if (attr == null) {
            return Policy.EMPTY;
        }

        // [게임 도메인 정책]
        if (attr.startsWith("GENSHIN_")) {
            // 게임 질문에는 학원/국비지원 등 교육 광고를 섞지 않도록 권고
            return new Policy(
                    setOf("GENSHIN_HYDRO", "GENSHIN_PYRO", "GENSHIN_ELECTRO", "GENSHIN_CRYO", "GENSHIN_WORLD"),
                    setOf("국비지원", "자바학원", "학원", "수강료", "커리큘럼")
            );
        }

        // [IT 도메인 정책]
        if (attr.startsWith("TECH_")) {
            if ("TECH_JAVA".equals(attr)) {
                // Java 질문에 Python 관련 키워드가 섞이는 것 방지
                return new Policy(
                        setOf("Spring", "Spring Boot", "JVM", "Bean", "DI", "IOC"),
                        setOf("pandas", "numpy", "pip", "conda")
                );
            }
            if ("TECH_PYTHON".equals(attr)) {
                // Python 질문에 Java 빌드도구 용어가 섞이는 것 방지
                return new Policy(
                        setOf("pandas", "numpy", "pip", "conda"),
                        setOf("maven", "gradle")
                );
            }
        }

        // [전자기기 도메인 정책 샘플]
        if (attr.startsWith("ELEC_")) {
            return new Policy(
                    setOf("사양", "스펙", "카메라", "배터리", "칩셋"),
                    Collections.emptySet()
            );
        }

        // 기본값: 제약 없음
        return Policy.EMPTY;
    }

    private static Set<String> setOf(String... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }

    /**
     * Lexicon 정책 레코드.
     * allowed / discouraged 는 힌트 수준이며, Guard/Reranker 에서 활용합니다.
     */
    public record Policy(Set<String> allowed, Set<String> discouraged) {
        public static final Policy EMPTY = new Policy(Collections.emptySet(), Collections.emptySet());
    }
}
