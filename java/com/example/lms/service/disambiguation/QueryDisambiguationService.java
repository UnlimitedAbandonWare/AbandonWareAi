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

    public DisambiguationResult clarify(String query, List<String> history) {
        // + 금지 조합(원신 × 에스코피에 등)은 재작성 금지 → 원문 그대로 반환
        if (NonGameEntityHeuristics.containsForbiddenPair(query)) {
            return fallback(query);
        }

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
        // - 기존 프롬프트
        // + 존재 여부 가드레일 추가
        return """
        You are an intent disambiguator. Return ONLY a JSON object with fields:
        ambiguousTerm, resolvedIntent, rewrittenQuery, confidence, score.
        If not ambiguous, set rewrittenQuery to the original query and confidence="low".

        RULES:
        - Do NOT invent characters/items/places that do not exist in the referenced domain (e.g., Genshin Impact).
        - If the user query includes a proper noun that likely DOES NOT exist in that domain,
          DO NOT rewrite. Keep the original query as rewrittenQuery and set confidence="low".
        - You may add a brief Korean note "(존재하지 않는 요소 가능성)" at the end of rewrittenQuery only if helpful.

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
}
