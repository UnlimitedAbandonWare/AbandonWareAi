package com.example.lms.service.disambiguation;

import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.service.llm.LlmClient;
import com.example.lms.prompt.DisambiguationPromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 단일 LLM 호출로 사용자의 질의를 분석하여 {@link DisambiguationResult}로 돌려주는 서비스.
 * <p>
 * 과거 버전에서는 게임/교육 이외의 일반 질의를 {@link NonGameEntityHeuristics} 로
 * 차단하거나, {@link DomainTermDictionary} 에 등록된 용어를 발견하면 LLM을
 * 우회(bypass)하는 방식이었으나, 이제는 다음 원칙을 따른다.
 * <ul>
 *     <li>NonGameEntityHeuristics 는 "소프트 힌트"로만 사용하고, 절대 질의를 차단하지 않는다.</li>
 *     <li>DomainTermDictionary 에서 찾은 보호어는 LLM 프롬프트에 힌트(seed)로만 제공한다.</li>
 *     <li>항상 LLM을 한 번 호출해 JSON → {@link DisambiguationResult} 로 역직렬화한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class QueryDisambiguationService {

    private static final Logger log = LoggerFactory.getLogger(QueryDisambiguationService.class);

    private final LlmClient llmClient;
    private final ObjectMapper om; // spring-boot-starter-json 기본 Bean
    private final DomainTermDictionary domainTermDictionary;
    private final DisambiguationPromptBuilder promptBuilder;

    /**
     * 사용자 질의와 최근 대화 히스토리를 기반으로 질의를 해석한다.
     *
     * @param query   사용자의 원본 질의
     * @param history 포맷팅된 최근 대화 히스토리 (가장 오래된 것부터)
     * @return LLM이 생성한 {@link DisambiguationResult}, 실패 시 fallback 결과
     */
    public DisambiguationResult clarify(String query, List<String> history) {
        if (query == null || query.isBlank()) {
            return fallback("");
        }

        // 1) 일반 도메인(비 게임/교육) 탐지 시 더 이상 차단하지 않고, 로그만 남긴다.
        if (NonGameEntityHeuristics.containsSuspiciousPair(query)) {
            log.debug("[Disambig] NonGameEntityHeuristics hit (SOFT): {}", query);
        }

        // 2) 보호어 기반 seed 생성 (LLM 호출을 우회하지 않는다)
        DisambiguationResult seed = null;
        Set<String> protectedTerms = Collections.emptySet();
        try {
            protectedTerms = domainTermDictionary.findKnownTerms(query);
            if (protectedTerms != null && !protectedTerms.isEmpty()) {
                seed = createSeedResult(query, protectedTerms);
                log.debug("[Disambig] seed created from protected terms: {}", protectedTerms);
            }
        } catch (Exception e) {
            log.debug("[Disambig] DomainTermDictionary lookup failed: {}", e.toString());
        }

        // 3) 범용 프롬프트 생성
        String prompt = promptBuilder.buildUniversal(query, history, seed);

        // 4) LLM 호출 및 JSON 파싱
        try {
            String raw = llmClient.complete(prompt);
            if (raw == null || raw.isBlank()) {
                log.warn("[Disambig] LLM returned blank response, falling back. query={}", query);
                return fallback(query);
            }

            String cleaned = sanitizeJson(raw);

            DisambiguationResult r = om.readValue(cleaned, DisambiguationResult.class);
            if (r == null) {
                log.warn("[Disambig] ObjectMapper produced null result, falling back. query={}", query);
                return fallback(query);
            }

            // 5) 필드 보정 및 기본값 설정
            if (r.getRewrittenQuery() == null || r.getRewrittenQuery().isBlank()) {
                r.setRewrittenQuery(query);
            }
            if (r.getConfidence() == null || r.getConfidence().isBlank()) {
                r.setConfidence("medium");
            }
            if (r.getDetectedCategory() == null || r.getDetectedCategory().isBlank()) {
                r.setDetectedCategory("UNKNOWN");
            }
            if (r.getAttributes() == null) {
                r.setAttributes(Collections.emptyMap());
            }

            return r;
        } catch (Exception e) {
            log.warn("[Disambig] LLM disambiguation failed, falling back. query={}, cause={}",
                    query, e.toString());
            return fallback(query);
        }
    }

    /**
     * DomainTermDictionary 에서 발견한 보호어를 기반으로 간단한 seed 결과를 생성한다.
     * 이 seed 는 프롬프트에 힌트로만 사용된다.
     */
    private DisambiguationResult createSeedResult(String query, Set<String> terms) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);

        if (terms != null && !terms.isEmpty()) {
            String first = terms.iterator().next();
            r.setTargetObject(first);
        }

        r.setDetectedCategory("DICTIONARY_TERM");
        r.setConfidence("high");
        r.setScore(1.0);
        return r;
    }

    /**
     * LLM 호출 실패 시 사용할 안전한 기본 결과.
     */
    
    /**
     * LLM이 JSON 응답을 ```json ... ``` 형태의 코드펜스로 감싸서 반환하는
     * 경우를 대비해, 앞뒤 코드펜스를 제거하고 공백을 정리한다.
     */
    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            // opening fence with optional language specifier
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            // closing fence
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        return t.strip();
    }
private DisambiguationResult fallback(String query) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);
        r.setConfidence("low");
        r.setScore(0.0);
        r.setDetectedCategory("UNKNOWN");
        r.setAttributes(Collections.emptyMap());
        return r;
    }

    // 과거 버전과의 호환성을 위해 남겨 두지만, 더 이상 사용하지 않는 우회 경로입니다.
    @SuppressWarnings("unused")
    private DisambiguationResult bypass(String query) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);
        r.setConfidence("high");
        r.setScore(1.0);
        return r;
    }
}
