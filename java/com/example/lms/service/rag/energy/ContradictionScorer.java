package com.example.lms.service.rag.energy;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;




/** 두 문장 조각의 상호 모순 정도를 0~1로 근사 */
@Component
public class ContradictionScorer {

    @Autowired(required = false)
    private ChatModel chatModel;

    public double score(String a, String b) {
        if (a == null || b == null) return 0.0;
        if (chatModel == null) return 0.0; // LLM 없으면 페널티 없음
        try {
            String prompt = """
                두 텍스트가 서로 모순되는지 0~1 점수로 평가하세요.
                0=모순 없음, 1=강한 모순. 숫자만.
                A: %s
                B: %s
                """.formatted(a, b);
            String out = chatModel
                    .chat(List.of(UserMessage.from(prompt)))
                    .aiMessage()
                    .text()
                    .trim();
            return clamp(Double.parseDouble(out), 0.0, 1.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}