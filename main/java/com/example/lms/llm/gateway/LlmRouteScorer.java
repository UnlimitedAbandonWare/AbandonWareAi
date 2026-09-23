package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class LlmRouteScorer {

    public int score(LlmRouterProperties.ModelConfig cfg, Collection<LlmFailureClass> failures) {
        int score = 100;
        if (cfg == null || !cfg.isEnabled()) {
            return 0;
        }
        if (cfg.isFallbackOnly()) {
            score -= 10;
        }
        if (cfg.getWeight() <= 0.0d) {
            score -= 15;
        }
        if (failures != null) {
            for (LlmFailureClass failure : failures) {
                score -= penalty(failure);
            }
        }
        return Math.max(0, Math.min(100, score));
    }

    public boolean eligible(int score, int minRouteScore, Collection<LlmFailureClass> failures) {
        if (failures != null) {
            for (LlmFailureClass failure : failures) {
                if (failure != null && failure.routeBlocking()) {
                    return false;
                }
            }
        }
        return score >= Math.max(0, minRouteScore);
    }

    public boolean hardBreakerFailure(LlmFailureClass failureClass) {
        return failureClass != null && failureClass.hardBreakerFailure();
    }

    private static int penalty(LlmFailureClass failure) {
        if (failure == null || failure == LlmFailureClass.NONE || failure == LlmFailureClass.CANCELLED_NEUTRAL) {
            return 0;
        }
        return switch (failure) {
            case RATE_LIMIT_COOLDOWN -> 20;
            case TIMEOUT_SOFT, SOFT_CIRCUIT_OPEN -> 25;
            case RESPONSE_MODEL_UNVERIFIED -> 10;
            case PROVIDER_ERROR, STREAM_ERROR, UNKNOWN -> 35;
            case AUTH_MISSING, HEALTH_DOWN, GPU_DEVICE_LOST, MODEL_MISSING, VRAM_OOM, DISABLED -> 100;
            case CONTEXT_TOO_SMALL, EMBEDDING_DIM_MISMATCH, LOCAL_UNSUPPORTED_MANAGED_RAG -> 100;
            case NONE, CANCELLED_NEUTRAL, BAD_REQUEST -> 0;
        };
    }
}
