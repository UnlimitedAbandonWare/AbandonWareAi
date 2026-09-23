package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouteScorerTest {

    private final LlmRouteScorer scorer = new LlmRouteScorer();

    @Test
    void cancelledAndRateLimitDoNotCountAsHardBreakerFailure() {
        assertFalse(scorer.hardBreakerFailure(LlmFailureClass.CANCELLED_NEUTRAL));
        assertFalse(scorer.hardBreakerFailure(LlmFailureClass.RATE_LIMIT_COOLDOWN));
    }

    @Test
    void softCircuitOpenDoesNotCountAsHardBreakerFailure() {
        assertFalse(scorer.hardBreakerFailure(LlmFailureClass.SOFT_CIRCUIT_OPEN));
    }

    @Test
    void modelHealthVramAndAuthAreRouteBlocking() {
        assertTrue(scorer.hardBreakerFailure(LlmFailureClass.MODEL_MISSING));
        assertTrue(scorer.hardBreakerFailure(LlmFailureClass.HEALTH_DOWN));
        assertTrue(scorer.hardBreakerFailure(LlmFailureClass.VRAM_OOM));
        assertTrue(scorer.hardBreakerFailure(LlmFailureClass.AUTH_MISSING));
    }

    @Test
    void blockingFailureMakesRouteIneligible() {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("gemma3:4b");
        cfg.setBaseUrl("http://localhost:11434/v1");

        int score = scorer.score(cfg, List.of(LlmFailureClass.AUTH_MISSING));

        assertFalse(scorer.eligible(score, 55, List.of(LlmFailureClass.AUTH_MISSING)));
    }
}
