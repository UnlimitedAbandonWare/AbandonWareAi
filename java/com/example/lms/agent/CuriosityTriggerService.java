package com.example.lms.agent;

import com.example.lms.llm.ChatModel;
import com.example.lms.service.chat.ChatHistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@Service
@RequiredArgsConstructor
public class CuriosityTriggerService {
    private static final Logger log = LoggerFactory.getLogger(CuriosityTriggerService.class);

    private final ChatModel chatModel;
    private final Optional<ChatHistoryService> chatHistory; // ✅ Optional로 변경
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Remove enclosing markdown code fences from a JSON string.  LLMs
     * sometimes return JSON wrapped in triple backticks, which causes
     * Jackson to fail parsing.  If the string begins with "```" then
     * the opening fence (with optional language tag) and the trailing
     * fence are removed.
     *
     * @param s raw response string from the LLM
     * @return sanitized JSON without fences
     */
    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        return t;
    }

    @Value("${agent.knowledge-curation.gap-prompt-max-chars:4000}")
    private int maxChars;

    public static record KnowledgeGap(
            String description,
            String initialQuery,
            String domain,
            String entityName
    ) {}

    public Optional<KnowledgeGap> findKnowledgeGap() {
        // ✅ Bean이 없으면 ""로, 있으면 요약본을 안전하게 처리
        String logs = chatHistory
                .map(svc -> safeTruncate(svc.summarizeRecentLowConfidence(50), maxChars))
                .orElse("");

        String prompt = """
                당신은 지식 큐레이터 에전트의 '호기심' 모듈입니다.
                아래 로그 요약에서, 시스템이 제대로 답하지 못한 '가장 중요한 지식 공백'을 하나만 추출하세요.
                JSON으로만 답하세요:
                {
                  "description": "/* ... */",
                  "initialQuery": "/* ... */",
                  "domain": "PRODUCT|GAME|GENERAL|EDU|/* ... */",
                  "entityName": "/* ... */"
                }
                
                [로그 요약]
                """ + logs;

        try {
            String json = chatModel.generate(prompt, 0.2, 400);
            // Sanitize JSON to strip code fences before parsing
            json = sanitizeJson(json);
            JsonNode n = om.readTree(json);
            KnowledgeGap gap = new KnowledgeGap(
                    text(n, "description"),
                    text(n, "initialQuery"),
                    text(n, "domain"),
                    text(n, "entityName")
            );
            if (gap.description().isBlank() || gap.initialQuery().isBlank()) return Optional.empty();
            return Optional.of(gap);
        } catch (Exception e) {
            log.debug("[Curiosity] parsing failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String key) {
        return (n != null && n.hasNonNull(key)) ? n.get(key).asText("") : "";
    }
    private static String safeTruncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}