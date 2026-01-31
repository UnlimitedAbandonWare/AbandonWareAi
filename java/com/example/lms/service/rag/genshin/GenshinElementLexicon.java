// src/main/java/com/example/lms/service/rag/genshin/GenshinElementLexicon.java
package com.example.lms.service.rag.genshin;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.example.lms.service.llm.LlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class GenshinElementLexicon {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GenshinElementLexicon.class);

    public GenshinElementLexicon(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * LLM 기반으로 텍스트에서 원신 캐릭터와 원소 속성을 추출합니다.
     * <p>
     * - 입력: 자유 형식 텍스트 (질문/대화 모두 가능)
     * - 출력: [{"name": "푸리나", "element": "HYDRO"}, ...] 형태의 리스트
     * <p>
     * LLM 응답이 JSON 코드 블록(```json ... ``` ) 형태인 경우에도 자동으로 파싱합니다.
     */
    public record CharacterElement(String name, String element) {
    }

    public java.util.List<CharacterElement> extractCharacters(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Collections.emptyList();
        }
        String prompt = """
                아래 텍스트에서 원신(Genshin Impact) 캐릭터 이름과 해당 원소 속성을 추출하세요.
                반드시 JSON 배열 형식으로만 응답하세요.
                - 필수 키: name, element
                - element 값은 PYRO, HYDRO, CRYO, ELECTRO, GEO, ANEMO, DENDRO 등
                  대문자 영문 코드만 사용하세요.
                - JSON 이외의 설명 문장은 절대 포함하지 마세요.

                텍스트: %s
                """.formatted(text);
        String raw = "";
        try {
            raw = llmClient.complete(prompt);
        } catch (Exception e) {
            log.warn("[GenshinLexicon] LLM 호출 실패: {}", e.toString());
            return java.util.Collections.emptyList();
        }
        if (raw == null || raw.isBlank()) {
            return java.util.Collections.emptyList();
        }
        String cleaned = raw.trim();
        // ```json ... ``` 형태 제거
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            java.util.List<CharacterElement> parsed = objectMapper.readValue(
                    cleaned,
                    new TypeReference<java.util.List<CharacterElement>>() {
                    });
            if (parsed == null) {
                return java.util.Collections.emptyList();
            }
            // element 필드를 대문자/양쪽 공백 트리밍
            java.util.List<CharacterElement> normalized = new java.util.ArrayList<>();
            for (CharacterElement ce : parsed) {
                if (ce == null)
                    continue;
                String name = ce.name() == null ? "" : ce.name().trim();
                String elem = ce.element() == null ? ""
                        : ce.element().trim()
                                .toUpperCase(java.util.Locale.ROOT);
                normalized.add(new CharacterElement(name, elem));
            }
            return java.util.Collections.unmodifiableList(normalized);
        } catch (Exception e) {
            log.warn("[GenshinLexicon] LLM JSON 파싱 실패: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    /** 캐릭터→원소 (ko/en 별칭을 모두 key로 둔다: lower-case 매칭) */
    private static final Map<String, String> NAME_TO_ELEM = Map.ofEntries(
            entry("에스코피에", "CRYO"), entry("escoffier", "CRYO"),
            entry("푸리나", "HYDRO"), entry("furina", "HYDRO"), entry("후리나", "HYDRO"),
            entry("다이루크", "PYRO"), entry("diluc", "PYRO"),
            entry("아를레키노", "PYRO"), entry("arlecchino", "PYRO")
    // Note: extend this lexicon as necessary.
    );

    /** 텍스트에서 직접 등장하는 원소 키워드(ko/en) → 표준 원소코드 */
    private static final Map<Pattern, String> ELEM_HINT = Map.of(
            Pattern.compile("(?i)\\bcryo|얼음|빙"), "CRYO",
            Pattern.compile("(?i)\\bpyro|불|화염"), "PYRO",
            Pattern.compile("(?i)\\bhydro|물"), "HYDRO",
            Pattern.compile("(?i)\\banemo|바람"), "ANEMO",
            Pattern.compile("(?i)\\belectro|번개"), "ELECTRO",
            Pattern.compile("(?i)\\bgeo|바위"), "GEO",
            Pattern.compile("(?i)\\bdendro|풀"), "DENDRO");

    public record Policy(Set<String> allowed, Set<String> discouraged) {
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        return Map.entry(k.toLowerCase(Locale.ROOT), v);
    }

    /**
     * [신규 추가] 텍스트에서 가장 먼저 발견되는 단일 원소를 추론하여 반환합니다.
     * ElementConstraintScorer에서 사용하기 위해 추가되었습니다.
     *
     * @param text 분석할 텍스트
     * @return 발견된 원소 코드 (e.g., "PYRO"), 없으면 null
     */
    public static String inferElement(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.toLowerCase(Locale.ROOT);

        // 1. 원소 키워드 직접 스캔
        for (var e : ELEM_HINT.entrySet()) {
            if (e.getKey().matcher(s).find()) {
                return e.getValue();
            }
        }
        // 2. 캐릭터 이름으로 스캔
        for (var e : NAME_TO_ELEM.entrySet()) {
            if (s.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 질의에서 주어(캐릭터) 추정 → 해당 원소 기반 정책 산출 */
    public Policy policyForQuery(String q) {
        if (q == null)
            return new Policy(Set.of(), Set.of());
        String s = q.toLowerCase(Locale.ROOT);
        String elem = null;
        for (String name : NAME_TO_ELEM.keySet()) {
            if (s.contains(name)) {
                elem = NAME_TO_ELEM.get(name);
                break;
            }
        }
        if (elem == null)
            return new Policy(Set.of(), Set.of());

        // ⚠ 정책은 보수적으로 유지(안전 가드레일 목적)
        if ("CRYO".equals(elem)) {
            // 요청 사례(에스코피에) 커버: HYDRO/ELECTRO 우선, PYRO/DENDRO 비선호
            return new Policy(Set.of("HYDRO", "ELECTRO"), Set.of("PYRO", "DENDRO"));
        }
        // 기타 캐릭터는 과도한 제약 방지
        return new Policy(Set.of(), Set.of());
    }

    /** 텍스트에 등장한 캐릭터들의 원소를 태깅 */
    public Set<String> tagElementsInText(String text) {
        if (text == null || text.isBlank())
            return Set.of();
        String s = text.toLowerCase(Locale.ROOT);
        Set<String> out = new HashSet<>();
        NAME_TO_ELEM.forEach((name, elem) -> {
            if (s.contains(name))
                out.add(elem);
        });
        for (var e : ELEM_HINT.entrySet()) {
            if (e.getKey().matcher(s).find())
                out.add(e.getValue());
        }
        return out;
    }

    /** 텍스트에 등장한 캐릭터 이름 수집(ko/en) */
    public Set<String> tagCharacters(String text) {
        if (text == null || text.isBlank())
            return Set.of();
        String s = text.toLowerCase(Locale.ROOT);
        return NAME_TO_ELEM.keySet().stream().filter(s::contains).collect(Collectors.toSet());
    }
}