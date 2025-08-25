package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.lms.llm.ModelCapabilities;
import org.springframework.util.StringUtils;
import java.time.Duration;

@Component
public class DynamicChatModelFactory {

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    /**
     * When set to {@code true} the application will route all OpenAI compatible chat
     * requests to a locally hosted LLM server.  The server must expose an
     * OpenAI‑compliant API at the configured {@code baseUrl} (for example
     * {@code http://localhost:11434/v1} for an Ollama instance).  When
     * {@code false} or unset the factory will fall back to the remote OpenAI
     * endpoints.
     */
    @Value("${local-llm.enabled:false}")
    private boolean localEnabled;

    /**
     * Base URL for the locally hosted LLM.  This should point to the root of
     * the OpenAI compatible API (e.g. {@code http://localhost:11434/v1}).  When
     * unspecified this property is ignored.
     */
    @Value("${local-llm.base-url:}")
    private String localBaseUrl;

    public ChatModel lc(String modelId, double temperature, double topP, Integer maxTokens) {
        // Construct a builder for the OpenAI chat model.  The temperature is
        // sanitised based on model capabilities to avoid invalid values.
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelId)
                .temperature(ModelCapabilities.sanitizeTemperature(modelId, temperature))
                .topP(topP)
                .timeout(Duration.ofSeconds(65));

        // Resolve the API key to use.  Prefer the value injected via
        // configuration (openai.api.key) but allow fallback to the OPENAI_API_KEY
        // environment variable for backwards compatibility.  When both are
        // missing an exception is thrown.
        String key = StringUtils.hasText(openaiApiKey) ? openaiApiKey : System.getenv("OPENAI_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("OpenAI API key is missing. Set 'openai.api.key' or ENV OPENAI_API_KEY.");
        }
        builder.apiKey(key);

        // When a local LLM is enabled and a base URL has been provided, redirect
        // the OpenAiChatModel to use the local endpoint.  LangChain4j will
        // automatically append /chat/completions to this base.  The base must
        // include the /v1 prefix for most servers (e.g. Ollama, LocalAI).
        if (localEnabled && localBaseUrl != null && !localBaseUrl.isBlank()) {
            builder = builder.baseUrl(localBaseUrl.trim());
        }

        // Apply the maximum token limit if provided.  A null or non‑positive
        // value indicates no explicit limit.
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
