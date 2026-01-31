package com.example.lms.service.llm.scheduler;

import com.example.lms.config.LlmConfig;
import com.example.lms.service.llm.ReactiveLlmClient;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple round‑robin scheduler for local LLM backends with a
 * fallback rule for long prompts.  When the approximate number of
 * input tokens or the maximum output tokens exceeds configurable
 * thresholds the request is routed to a tensor‑parallel backend
 * (identified by an id ending with "tp2").  Otherwise the request
 * is distributed in round‑robin fashion across single‑GPU backends.
 *
 * <p>This scheduler does not perform health checks or load
 * balancing; those concerns are handled separately by
 * {@link com.example.lms.service.llm.LlmRouterService}.  To use
 * this scheduler inject it alongside a reactive LLM client and call
 * {@link #route(ReactiveLlmClient.LlmRequest)} to obtain a target
 * backend.</p>
 */
@Component
public class DualGpuScheduler {
    private final LlmConfig config;
    private final AtomicInteger rrIndex = new AtomicInteger();

    public DualGpuScheduler(LlmConfig config) {
        this.config = config;
    }

    /**
     * Choose a backend for the given request.  Requests with a large
     * token count are routed to the TP2 backend (id ending in
     * "tp2"); other requests are balanced across all other
     * backends.  If no TP2 backend is configured the request is
     * always balanced across all backends.
     */
    public ReactiveLlmClient.Backend route(ReactiveLlmClient.LlmRequest req) {
        int inTok = req.approxInputTokens();
        int outTok = req.maxTokens();
        // Try to find a TP2 backend only when the request is large.
        if ((inTok > 4000 || outTok > 1024) && config.getBackends() != null) {
            for (LlmConfig.Backend b : config.getBackends()) {
                if (b.getId() != null && b.getId().endsWith("tp2")) {
                    return b;
                }
            }
        }
        // Otherwise pick the next non‑TP2 backend in round‑robin order.
        List<LlmConfig.Backend> list = config.getBackends();
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("llm.backends not configured");
        }
        List<LlmConfig.Backend> nonTp2 = list.stream()
            .filter(b -> b.getId() == null || !b.getId().endsWith("tp2"))
            .toList();
        int idx = Math.floorMod(rrIndex.getAndIncrement(), nonTp2.size());
        return nonTp2.get(idx);
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}