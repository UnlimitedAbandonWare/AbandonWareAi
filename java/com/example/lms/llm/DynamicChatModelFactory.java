package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.time.Duration;



@Component
public class DynamicChatModelFactory {

    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String remoteBaseUrl;     // '/v1' 없는 루트 기대

    @Value("${local-llm.enabled:false}")
    private boolean localEnabled;

    @Value("${local-llm.base-url:}")
    private String localBaseUrl;      // '/v1' 포함 기대

    private static String ensureNoTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
    private static String ensureV1(String baseNoV1) {
        String base = ensureNoTrailingSlash(baseNoV1);
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    public ChatModel lc(String modelId, double temperature, double topP, Integer maxTokens) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("[LLM] API key missing (openai.api.key/OPENAI_API_KEY)");
        }

        String baseUrl = localEnabled && StringUtils.hasText(localBaseUrl)
                ? localBaseUrl
                : ensureV1(remoteBaseUrl);

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelId)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .temperature(ModelCapabilities.sanitizeTemperature(modelId, temperature))
                .topP(topP)
                .timeout(Duration.ofSeconds(65));

        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }

    public String effectiveModelName(ChatModel model) {
        try {
            if (model instanceof OpenAiChatModel m) {
                try {
                    var f = m.getClass().getDeclaredField("modelName");
                    f.setAccessible(true);
                    Object v = f.get(m);
                    if (v != null) return v.toString();
                } catch (NoSuchFieldException ignore) {}
            }
            String s = String.valueOf(model);
            if (s.contains("gpt-")) return s;
            return model.getClass().getSimpleName();
        } catch (Throwable t) {
            return model.getClass().getSimpleName();
        }
    }
}