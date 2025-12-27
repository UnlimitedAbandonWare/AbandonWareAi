package com.example.lms.config.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 지능형 전처리(Cognitive / Guardrail) 관련 설정.
 * - rag.cognitive.enabled: 기능 온/오프
 * - rag.cognitive.model-id: 사용할 LLM 라우터 모델 ID (예: "light")
 * - rag.cognitive.max-tokens: 요약/분류에 사용할 최대 토큰 수
 * - rag.cognitive.timeout-ms: LLM 호출 타임아웃 (ms)
 */
@Component
@ConfigurationProperties(prefix = "rag.cognitive")
public class RagCognitiveProperties {

    /**
     * 지능형 전처리 기능 사용 여부 (기본값: 사용)
     */
    private boolean enabled = true;

    /**
     * LLM 라우터에 전달할 모델 ID.
     * 기본값은 "light"로 두고, 라우터 쪽에서 qwen2.5-7b-instruct 등으로 매핑한다.
     */
    private String modelId = "light";

    /** 분류용 최대 토큰 수 */
    private int maxTokens = 512;

    /** LLM 호출 타임아웃(ms) */
    private long timeoutMs = 5000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
