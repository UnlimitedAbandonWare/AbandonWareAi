package com.example.lms.service.rag;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;




@Component
@RequiredArgsConstructor
public class SelfAskPlanner {
    // Always use the mini/low‑tier model for self‑ask planning to control
    // latency and avoid unregistered high‑tier models.  The qualifier
    // ensures Spring injects the "mini" ChatModel bean defined in ModelConfig.
    @Qualifier("mini")
    private final ChatModel chatModel;

    /**
     * 질문을 1~2개의 간결한 웹 검색 질의로 변환합니다.
     * - 고유명사/핵심 키워드만 남깁니다.
     * - 따옴표/불릿/번호 등을 제거합니다.
     */
    public List<String> plan(String question, int max) {
        if (question == null || question.isBlank()) return List.of();

        String sys = "너는 Self-Ask 검색 플래너다. 사용자의 질문을 한국어 웹 검색에 적합한 "
                + "1~2개의 짧은 질의로 변환하되, 고유명사와 핵심 키워드만 남겨라. "
                + "불릿, 따옴표, 접두사는 쓰지 말고 줄바꿈으로만 나열해라.";

        String out = chatModel.chat(List.of(
                SystemMessage.from(sys),
                UserMessage.from(question)
        )).aiMessage().text();

        // 후처리: 응답을 파싱하고 중복 제거
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String line : out.split("\\r?\\n+|•|·|\\u2022")) {
            String s = line.trim();
            if (!s.isEmpty()) uniq.add(s);
            if (uniq.size() >= Math.max(1, max)) break;
        }
        return new ArrayList<>(uniq);
    }


// === Added by v12: Self‑Ask 3‑Way lanes (BQ, ER, RC) ===
    public enum SubQuestionType { BQ, ER, RC }
    public static final class SubQuestion {
        public final SubQuestionType type;
        public final String text;
        public final java.util.Map<String,Object> meta;
        public SubQuestion(SubQuestionType type, String text, java.util.Map<String,Object> meta){
            this.type = type; this.text = text; this.meta = meta==null? java.util.Map.of(): meta;
        }
        @Override public String toString(){ return type+":"+text; }
    }
    private String laneSystemPrompt(SubQuestionType lane){
        return switch(lane){
            case BQ -> "You expand the user's query into EXACTLY ONE precise subquestion to set domain and definitions. Output only the subquestion line.";
            case ER -> "You expand the user's query into EXACTLY ONE subquestion that enumerates aliases, spelling variants, and acronyms. Output only the subquestion line.";
            case RC -> "You expand the user's query into EXACTLY ONE subquestion that targets relations/causal links among key entities. Output only the subquestion line.";
        };
    }
    public java.util.List<SubQuestion> generateThreeLanes(String query, long timeoutMs){
        java.util.ArrayList<SubQuestion> out = new java.util.ArrayList<>();
        SubQuestionType[] lanes = new SubQuestionType[]{SubQuestionType.BQ, SubQuestionType.ER, SubQuestionType.RC};
        for (SubQuestionType lane : lanes){
            String sys = laneSystemPrompt(lane);
            String sub = chatModel.chat(java.util.List.of(
                dev.langchain4j.data.message.SystemMessage.from(sys),
                dev.langchain4j.data.message.UserMessage.from(query)
            )).aiMessage().text().trim();
            if (!sub.isEmpty()){
                out.add(new SubQuestion(lane, sub, java.util.Map.of("lane", lane.name())));
            }
        }
        return out;
    }
    // === End of v12 addition ===
}