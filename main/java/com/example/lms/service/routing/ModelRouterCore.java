package com.example.lms.service.routing;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ModelRouterCore {
    private static final Logger log = LoggerFactory.getLogger(ModelRouterCore.class);

    @Value("${llm.chat-model:gemma4:26b}")
    private String defaultModel;
@Value("${llm.latest-tech-model:}")
private String latestTechModel;


    @Value("${llm.base-url:http://localhost:11434/v1}")
    private String configuredBaseUrl;

    public String chooseModel(RouteSignal s) {
        // 외부 힌트에 의존하지 않고 구성 기본값 우선
        return normalizeModelId(defaultModel);
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return ModelCapabilities.normalizeLocalModelAlias(defaultModel);
        }
        String raw = modelId.trim();
        String id  = raw.toLowerCase();

        // 임베딩/레거시 전용 모델은 채팅에 직접 쓰지 않음 → 경고 + defaultModel 대체
        if (id.equals("babbage-002") || id.contains("embedding")) {
            log.warn("[ModelRouterCore] embedding_or_legacy_model requestedModelHash={} requestedModelLength={} defaultModelHash={} defaultModelLength={} action=use_default_model",
                    SafeRedactor.hashValue(raw), lengthOf(raw), SafeRedactor.hashValue(defaultModel), lengthOf(defaultModel));
            return ModelCapabilities.normalizeLocalModelAlias(defaultModel);
        }

        // OpenAI 계열(gpt-*, o3-*)은 그대로 사용
        if (id.startsWith("gpt-") || id.startsWith("o3-")) {
            return raw;
        }

        // 그 외(로컬 모델, qwen, gemma 등)는 있는 그대로 사용
        return raw;
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    public String chooseBaseUrl(RouteSignal s) {
        String v = configuredBaseUrl;
        if (v == null || v.isBlank()) v = "http://localhost:11434/v1";
        if (!v.endsWith("/v1")) v = v.replaceAll("/+$", "") + "/v1";
        return v;
    }
}
