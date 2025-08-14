package com.example.lms.service.rag.pre;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;

/** LLM/휴리스틱으로 CognitiveState 추출 */
@Component
public class CognitiveStateExtractor {

    @Autowired(required = false)
    private ChatModel chatModel;

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

        return new CognitiveState(abs, tmp, evid, cb);
    }

    private static boolean containsAny(String s, String needle) {
        return s != null && s.contains(needle);
    }
}
