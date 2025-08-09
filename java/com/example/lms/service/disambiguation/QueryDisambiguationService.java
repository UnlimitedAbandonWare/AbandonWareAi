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
        return """
        You are an intent disambiguator. Return ONLY a JSON object with fields:
        ambiguousTerm, resolvedIntent, rewrittenQuery, confidence, score.
        If not ambiguous, set rewrittenQuery to the original query and confidence="low".

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
