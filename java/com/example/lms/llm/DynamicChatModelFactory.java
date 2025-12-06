package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DynamicChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicChatModelFactory.class);

    @Value("${llm.base-url-gemma:http://localhost:11434/v1}")
    private String baseUrlGemma;
    @Value("${llm.base-url-qwen:http://localhost:11435/v1}")
    private String baseUrlQwen;
    @Value("${llm.api-key:}")
    private String llmApiKey;

    private String normalizeBase(String url) {
        if (url == null || url.isBlank()) return "http://localhost:11434/v1";
        String base = url.trim();
        if (!base.endsWith("/v1")) {
            base = base.replaceAll("/+$", "") + "/v1";
        }
        return base;
    }
    private String classifyBackend(String modelName, String baseUrl) {
        String baseLower = baseUrl == null ? "" : baseUrl.toLowerCase();
        String modelLower = modelName == null ? "" : modelName.toLowerCase();
        if (baseLower.contains("api.openai.com") || baseLower.contains("groq.com")) {
            return "remote-openai";
        }
        if (baseLower.contains("localhost") || baseLower.contains("127.0.0.1")) {
            if (modelLower.contains("qwen") || modelLower.contains("llama")) {
                return "local-vllm-3060";
            }
            return "local-vllm-3090";
        }
        return "unknown";
    }



    /** OpenAI-compatible (Ollama 등) 베이스 URL로 ChatModel 생성 */
    public ChatModel build(String modelName) {
        String effectiveModel = (modelName == null || modelName.isBlank()) ? "gemma3:27b" : modelName;
        String base;
        if (effectiveModel.toLowerCase().contains("qwen")) {
            base = normalizeBase(baseUrlQwen);
        } else {
            base = normalizeBase(baseUrlGemma);
        }
        String backendTag = classifyBackend(effectiveModel, base);
        // MERGE_HOOK:PROJ_AGENT::BACKEND_PATH_LLM_BUILD
        log.info("BACKEND_PATH: llm='{}', backend='{}', baseUrl='{}'", effectiveModel, backendTag, base);

        try {
            return OpenAiChatModel.builder()
                    .baseUrl(base)
                    .apiKey(llmApiKey == null ? "" : llmApiKey)
                    .modelName(effectiveModel)
                    .build();
        } catch (Exception e) {
            throw wrapConnect(e, base);
        }
    }

    private RuntimeException wrapConnect(Exception e, String baseUrl) {
        String m = "Failed to connect to local LLM at " + baseUrl +
                " (check: Ollama process up? '/v1' suffix? firewall/port 11434/11435)";
        log.warn(m + " - " + e.getMessage());
        return new IllegalStateException(m, e);
    }

    /** Backward-compat factory: preserve legacy signature used by services.
     *  Nullables are ignored; defaults come from model/server side. */
    public ChatModel lc(String modelName, Double temperature, Double topP, Integer maxTokens) {
        String effectiveModel = (modelName == null || modelName.isBlank()) ? "gemma3:27b" : modelName;
        String base;
        if (effectiveModel.toLowerCase().contains("qwen")) {
            base = normalizeBase(baseUrlQwen);
        } else {
            base = normalizeBase(baseUrlGemma);
        }
        String backendTag = classifyBackend(effectiveModel, base);
        // MERGE_HOOK:PROJ_AGENT::BACKEND_PATH_LLM_LC
        log.info("BACKEND_PATH: llm='{}', backend='{}', baseUrl='{}'", effectiveModel, backendTag, base);

        try {
            var b = OpenAiChatModel.builder()
                    .baseUrl(base)
                    .apiKey(llmApiKey == null ? "" : llmApiKey)
                    .modelName(effectiveModel);
            if (temperature != null) { b = b.temperature(temperature); }
            if (topP != null)       { b = b.topP(topP); }
            if (maxTokens != null)  { b = b.maxTokens(maxTokens); }
            return b.build();
        } catch (Exception e) {
            throw wrapConnect(e, base);
        }
    }
    
}
