package com.example.lms.service.disambiguation;

import com.example.lms.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueryDisambiguationService {
    private final LlmClient llmClient;
    private final ObjectMapper om; // spring-boot-starter-json 기본 Bean
    // 도메인 사전: 알려진 고유명사는 재작성 금지
    private final com.example.lms.service.correction.DomainTermDictionary domainTermDictionary;
    public DisambiguationResult clarify(String query, List<String> history) {
        // + 금지 조합(원신 × 에스코피에 등)은 재작성 금지 → 원문 그대로 반환
        if (NonGameEntityHeuristics.containsForbiddenPair(query)) {
            return fallback(query);
        }
        //  사전 보호어가 포함되어 있으면 LLM 호출 없이 원문 유지
        try {
            var protectedTerms = domainTermDictionary.findKnownTerms(query);
            if (protectedTerms != null && !protectedTerms.isEmpty()) {
                return bypass(query);
            }
        } catch (Exception ignore) {}

        String prompt = buildPrompt(query, history);
        String raw = "";
        try {
            raw = llmClient.complete(prompt);
            if (raw == null || raw.isBlank()) return fallback(query);
            DisambiguationResult r = om.readValue(raw, DisambiguationResult.class);
            return (r == null) ? fallback(query) : r;
        } catch (Exception e) {
            return fallback(query);
        }
    }

    private String buildPrompt(String query, List<String> history) {
        String hist = (history == null || history.isEmpty()) ? "" : String.join("\n", history);
        // 존재 여부 가드레일 강화: 보호어는 유지, 추측성 꼬리표 금지
        return """
        You are an intent disambiguator. Return ONLY a JSON object with fields:
        ambiguousTerm, resolvedIntent, rewrittenQuery, confidence, score.
        If not ambiguous, set rewrittenQuery to the original query and confidence="low".

        RULES:
        - Do NOT invent characters/items/places that do not exist in the referenced domain (e.g., Genshin Impact).
                                  - If the user query includes a proper noun that the system already recognizes (in-domain dictionary),
                                                                                                                                                                                                                                                                                                                                                DO NOT rewrite or append any notes. Keep the original query as rewrittenQuery and set confidence="high".
                                                                                                                                                                                                                                                                                                                                              - Do not append speculative notes such as "(존재하지 않는 요소 가능성)". Such notes are prohibited.

        [Conversation history, oldest→latest]
        %s

        [Current query]
        %s
        """.formatted(hist, query);
    }

    private DisambiguationResult fallback(String query) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);
        r.setConfidence("low");
        r.setScore(0.0);
        return r;
    }

    //  +보호어 포함 시 사용하는 우회 결과
    private DisambiguationResult bypass(String query) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);
        r.setConfidence("high");
        r.setScore(1.0);
        return r;
    }
}