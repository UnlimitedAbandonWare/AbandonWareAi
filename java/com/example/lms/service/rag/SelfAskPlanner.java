package com.example.lms.service.rag;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SelfAskPlanner {

    private static final Logger log = LoggerFactory.getLogger(SelfAskPlanner.class);

    // Always use the mini/low-tier model for self-ask planning to control
    // latency and avoid unregistered high-tier models.
    @Qualifier("localChatModel")
    private final ChatModel chatModel;

    // SearchPlan 클래스는 더 이상 사용되지 않으므로 제거함

    /**
     * 사용자의 질문을 1~N개의 간결한 웹 검색 질의로 변환한다.
     * Structured Output 미지원으로 인해 프롬프트 지시(줄바꿈 구분) 및 문자열 파싱 방식을 사용한다.
     */
    public List<String> plan(String question, int max) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, max);

        String sys = "너는 Self-Ask 검색 플래너다. 사용자의 질문을 한국어 웹 검색에 적합한 "
                + "1~2개의 짧은 질의로 변환하되, 고유명사와 핵심 키워드만 남겨라. "
                + "불릿, 따옴표, 접두사는 쓰지 말고 줄바꿈으로만 나열해라.";

        LinkedHashSet<String> uniq = new LinkedHashSet<>();

        try {
            // Structured Output 호출(withStructuredOutput)을 제거하고
            // 일반 chat 호출 후 문자열을 파싱하는 방식으로 변경
            String out = chatModel.chat(List.of(
                    SystemMessage.from(sys),
                    UserMessage.from(question)
            )).aiMessage().text();

            fallbackSplitToSet(out, uniq, limit);
        } catch (Exception e) {
            log.warn("[SelfAskPlanner] Planning failed: {}", e.toString());
        }

        return new ArrayList<>(uniq);
    }

    /**
     * 기존 문자열 split 기반 로직을 별도 메서드로 추출한 구현.
     */
    private void fallbackSplitToSet(String raw, LinkedHashSet<String> uniq, int max) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String s = line == null ? "" : line.trim();
            if (!s.isEmpty()) {
                uniq.add(s);
            }
            if (uniq.size() >= Math.max(1, max)) {
                break;
            }
        }
    }

    // === Existing 3-way Self-Ask lanes (BQ, ER, RC) ===

    public enum SubQuestionType { BQ, ER, RC }

    public static final class SubQuestion {
        public final SubQuestionType type;
        public final String text;
        public final java.util.Map<String, Object> meta;

        public SubQuestion(SubQuestionType type, String text, java.util.Map<String, Object> meta) {
            this.type = type;
            this.text = text;
            this.meta = meta == null ? java.util.Map.of() : meta;
        }

        @Override
        public String toString() {
            return type + ":" + text;
        }
    }

    private String laneSystemPrompt(SubQuestionType lane) {
        return switch (lane) {
            case BQ -> "You expand the user's query into EXACTLY one clarifying question focusing on high-level background and scope. Output only the subquestion line.";
            case ER -> "You expand the user's query into EXACTLY one clarifying question focusing on entities, relations, and aliases. Output only the subquestion line.";
            case RC -> "You expand the user's query into EXACTLY one clarifying question focusing on resolving ambiguities and missing context. Output only the subquestion line.";
        };
    }

    public List<SubQuestion> generateThreeLanes(String query, long timeoutMs) {
        ArrayList<SubQuestion> out = new ArrayList<>();
        SubQuestionType[] lanes = new SubQuestionType[]{SubQuestionType.BQ, SubQuestionType.ER, SubQuestionType.RC};
        for (SubQuestionType lane : lanes) {
            String sys = laneSystemPrompt(lane);
            try {
                String sub = chatModel.chat(List.of(
                        SystemMessage.from(sys),
                        UserMessage.from(query)
                )).aiMessage().text().trim();
                if (!sub.isEmpty()) {
                    out.add(new SubQuestion(lane, sub, java.util.Map.of("lane", lane.name())));
                }
            } catch (Exception e) {
                log.warn("[SelfAskPlanner] Lane {} generation failed: {}", lane, e.toString());
            }
        }
        return out;
    }
}
