package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DynamicChatModelFactory {

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    public ChatModel lc(String modelId, double temperature, double topP, Integer maxTokens) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelId)
                .temperature(temperature)
                .topP(topP);

        String key = StringUtils.hasText(openaiApiKey) ? openaiApiKey : System.getenv("OPENAI_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("OpenAI API key is missing. Set 'openai.api.key' or ENV OPENAI_API_KEY.");
        }
        builder.apiKey(key);

        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }

    /**  추천(Recommender) 작업용 보수적 세팅(temperature ≤ 0.2, topP=1.0) */
    public ChatModel lcWithPolicy(String intent,
                                  String modelId,
                                  double temperature,
                                  double topP,
                                  Integer maxTokens) {
        if ("RECOMMENDATION".equalsIgnoreCase(intent)) {
            temperature = Math.min(temperature, 0.2);
            topP = 1.0;
        }
        return lc(modelId, temperature, topP, maxTokens);
    }

    /** ✅ ChatModel 인스턴스에서 실제 modelName을 최대한 복원 */
    public String effectiveModelName(ChatModel model) {
        try {
            // OpenAiChatModel (langchain4j) 우선
            if (model instanceof OpenAiChatModel m) {
                // 1) 공개 메서드
                try {
                    var meth = m.getClass().getDeclaredMethod("modelName");
                    meth.setAccessible(true);
                    Object v = meth.invoke(m);
                    if (v != null) return v.toString();
                } catch (NoSuchMethodException ignore) {}
                // 2) 필드 리플렉션
                try {
                    var f = m.getClass().getDeclaredField("modelName");
                    f.setAccessible(true);
                    Object v = f.get(m);
                    if (v != null) return v.toString();
                } catch (NoSuchFieldException ignore) {}
            }
            // 기타 모델: toString() 내 표기가 있으면 사용
            String s = String.valueOf(model);
            if (s.contains("gpt-")) return s;
            return model.getClass().getSimpleName();
        } catch (Throwable t) {
            return model.getClass().getSimpleName();
        }
    }
}
