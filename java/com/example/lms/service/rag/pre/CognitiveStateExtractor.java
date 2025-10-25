package com.example.lms.service.rag.pre;

import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.common.InputTypeScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import com.example.lms.service.rag.QueryComplexityGate;





/** LLM/휴리스틱으로 CognitiveState 추출 */
@Component
public class CognitiveStateExtractor {

    @Autowired(required = false)
    private ChatModel chatModel;

    // Persona decision helpers
    @Autowired
    private com.example.lms.service.rag.QueryComplexityGate queryComplexityGate;


    public CognitiveState extract(String query) {
        // 휴리스틱 기본값
        CognitiveState.AbstractionLevel abs = query.matches(".*(비교|장단점|vs|대비).*")
                ? CognitiveState.AbstractionLevel.COMPARATIVE
                : (query.matches(".*(방법|절차|방법론|how).*")
                ? CognitiveState.AbstractionLevel.PROCEDURAL
                : (query.length() < 20
                ? CognitiveState.AbstractionLevel.FACTUAL
                : CognitiveState.AbstractionLevel.SUMMARY));

        CognitiveState.TemporalSensitivity tmp = query.matches(".*(최신|업데이트|패치|news).*")
                ? CognitiveState.TemporalSensitivity.RECENT_REQUIRED
                : CognitiveState.TemporalSensitivity.IRRELEVANT;

        List<String> evid = new ArrayList<>();
        if (query.matches(".*(통계|수치|지표).*")) evid.add("통계");
        if (query.matches(".*(후기|리뷰).*")) evid.add("사용자 후기");
        if (query.matches(".*(공식|스펙|사양).*")) evid.add("공식 문서/기술 사양");
        if (evid.isEmpty()) evid.add("일반 근거");

        CognitiveState.ComplexityBudget cb = query.length() > 60
                ? CognitiveState.ComplexityBudget.HIGH
                : (query.length() > 30 ? CognitiveState.ComplexityBudget.MEDIUM : CognitiveState.ComplexityBudget.LOW);

        // LLM이 있으면 한 번 보정
        if (chatModel != null) {
            try {
                String prompt = """
                    사용자 질문을 다음 카테고리로 분류해 JSON으로만 응답:
                    - abstraction: [SUMMARY, PROCEDURAL, FACTUAL, COMPARATIVE]
                    - temporal: [RECENT_REQUIRED, HISTORICAL, IRRELEVANT]
                    - evidence: 쉼표로 1~3개 (예: "공식 문서, 기술 사양")
                    - complexity: [LOW, MEDIUM, HIGH]
                    질문: "%s"
                    """.formatted(query);
                String json = chatModel
                        .chat(java.util.List.of(UserMessage.from(prompt)))
                        .aiMessage()
                        .text();
                // 간단 파싱(실패 시 휴리스틱 유지)
                String j = json.toUpperCase();
                abs = containsAny(j, "COMPARATIVE") ? CognitiveState.AbstractionLevel.COMPARATIVE
                        : (containsAny(j, "PROCEDURAL") ? CognitiveState.AbstractionLevel.PROCEDURAL
                        : (containsAny(j, "FACTUAL") ? CognitiveState.AbstractionLevel.FACTUAL
                        : CognitiveState.AbstractionLevel.SUMMARY));

                tmp = containsAny(j, "RECENT_REQUIRED") ? CognitiveState.TemporalSensitivity.RECENT_REQUIRED
                        : (containsAny(j, "HISTORICAL") ? CognitiveState.TemporalSensitivity.HISTORICAL
                        : CognitiveState.TemporalSensitivity.IRRELEVANT);

                if (json.contains("evidence")) {
                    evid.clear();
                    String ev = json.replaceAll("(?is).*\"evidence\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    for (String e : ev.split("\\s*,\\s*")) if (!e.isBlank()) evid.add(e.trim());
                }

                cb = containsAny(j, "HIGH") ? CognitiveState.ComplexityBudget.HIGH
                        : (containsAny(j, "MEDIUM") ? CognitiveState.ComplexityBudget.MEDIUM
                        : CognitiveState.ComplexityBudget.LOW);
            } catch (Exception ignore) {}
        }

        // Determine whether the current input originated from a voice dictation.
        boolean voice = false;
        try {
            String it = InputTypeScope.current();
            voice = it != null && it.equalsIgnoreCase("voice");
        } catch (Exception ignore) {
            // default to false if scope is unavailable
        }

        // Persona heuristics: choose a conversational persona based on complexity and intent
        String persona = null;
        try {
            QueryComplexityGate.Level level = queryComplexityGate != null
                    ? queryComplexityGate.assess(query)
                    : QueryComplexityGate.Level.AMBIGUOUS;
            String intent = inferIntent(query);
            // Simple rules: complex queries → analyzer; recommendation/pairing → tutor; otherwise tutor by default
            if (level == QueryComplexityGate.Level.COMPLEX) {
                persona = "analyzer";
            } else if ("RECOMMENDATION".equalsIgnoreCase(intent) || "PAIRING".equalsIgnoreCase(intent)) {
                persona = "tutor";
            } else {
                persona = "tutor";
            }
        } catch (Exception ignore) {
            persona = null;
        }

        // Determine execution mode: switch to vector search when education keywords are present
        CognitiveState.ExecutionMode execMode = CognitiveState.ExecutionMode.KEYWORD_SEARCH;
        try {
            String lower = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
            // 교육/학원 관련 키워드 감지: '학원' (academy) 또는 '국비' (government subsidy)
            if (lower.contains("학원") || lower.contains("국비")) {
                execMode = CognitiveState.ExecutionMode.VECTOR_SEARCH;
            }
        } catch (Exception ignore) {
            execMode = CognitiveState.ExecutionMode.KEYWORD_SEARCH;
        }

        return new CognitiveState(abs, tmp, evid, cb, voice, persona, execMode);
    }

    private static boolean containsAny(String s, String needle) {
        return s != null && s.contains(needle);
    }
    private String inferIntent(String q) {
        if (q == null || q.isBlank()) return "GENERAL";
        String s = q.toLowerCase(java.util.Locale.ROOT);
        if (s.matches(".*(잘\\s*어울리|어울리(?:는|다)?|궁합|상성|시너지|조합|파티).*")) return "PAIRING";
        if (s.matches(".*(추천|픽|티어|메타).*")) return "RECOMMENDATION";
        return "GENERAL";
    }
}