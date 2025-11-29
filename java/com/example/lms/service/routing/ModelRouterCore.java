package com.example.lms.service.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelRouterCore {

    @Value("${llm.chat-model:gemma3:27b}")
    private String defaultModel;

    @Value("${llm.base-url:http://localhost:11434/v1}")
    private String configuredBaseUrl;

    public String chooseModel(RouteSignal s) {
        // 외부 힌트에 의존하지 않고 구성 기본값 우선
        return normalizeModelId(defaultModel);
    }

    private String normalizeModelId(String modelId) {
        // MERGE_HOOK:PROJ_AGENT::MODEL_ALIAS_NORMALIZE
        // 관점2(BACKEND_PATH): 클라이언트가 babbage-002 / gpt-3.5-turbo 등을 요청해도
        // 기본 경로는 "로컬 LLM"을 타도록 강제한다.
        if (modelId == null || modelId.isBlank()) {
            return defaultModel;
        }
        String raw = modelId.trim();
        String id  = raw.toLowerCase();

        // 옛 OpenAI alias들을 모두 기본 로컬 모델로 강제 매핑
        if ("babbage-002".equals(id)
                || "gpt-3.5-turbo".equals(id)
                || "gpt-4o-mini".equals(id)
                || "gpt-4o".equals(id)) {
            return defaultModel;
        }
        // 기타 "gpt-" prefix는 일단 defaultModel로 정규화 (필요시 세분화 가능)
        if (id.startsWith("gpt-")) {
            return defaultModel;
        }
        return raw;
    }

    public String chooseBaseUrl(RouteSignal s) {
        String v = configuredBaseUrl;
        if (v == null || v.isBlank()) v = "http://localhost:11434/v1";
        if (!v.endsWith("/v1")) v = v.replaceAll("/+$", "") + "/v1";
        return v;
    }
}
