package com.example.lms.service.rag;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SelfAskPlanner {
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
}