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
    // Prompt builder used to construct the disambiguation prompt.  Delegating
    // prompt assembly to a dedicated builder ensures uniformity across
    // services and eases future modifications to the prompt format.
    private final com.example.lms.prompt.DisambiguationPromptBuilder promptBuilder;

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

        String prompt = promptBuilder.build(query, history);
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

    // The buildPrompt method has been removed.  Prompt construction is now
    // delegated to {@link DisambiguationPromptBuilder} to avoid inlined
    // multi-line strings and to centralise prompt management.

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