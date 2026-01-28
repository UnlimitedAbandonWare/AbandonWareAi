
package com.abandonware.ai.agent.service.llm;

import com.abandonware.ai.agent.model.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-first router: try local OpenAI-compatible endpoint(s), then fallback to remote (OpenAI/Groq).
 * - Does NOT start any local process. It only calls configured HTTP endpoints.
 * - Local endpoints are defined under llmrouter.models.*.base-url
 * - Fallback toggle: llmrouter.fallback (default: false)
 */
@Service
public class LlmRouterService {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);

    // --- Local routing props ---
    @Value("${llmrouter.fallback:false}")
    private boolean fallbackEnabled;

    @Value("${llmrouter.models.gemma.base-url:http://localhost:11434/v1}")
    private String gemmaBaseUrl;
    @Value("${llmrouter.models.qwen.base-url:}")
    private String qwenBaseUrl;

    @Value("${llmrouter.models.mistral.base-url:}")
    private String mistralBaseUrl;

    @Value("${llmrouter.models.light.base-url:}")
    private String lightBaseUrl;

    @Value("${llmrouter.cooldown-ms:20000}")
    private long cooldownMs;

    // --- Local model settings ---
    @Value("${llm.local.chat-model:llama-3.1-8b-instruct}")
    private String localModelId;

    @Value("${llm.local.temperature:0.2}")
    private double localTemperature;

    @Value("${llm.local.timeout-ms:15000}")
    private long localTimeoutMs;

    // --- Remote (fallback) settings ---
    @Value("${llm.base-url:http://localhost:11434/v1}")
    private String remoteBaseUrl;

    @Value("${llm.api-key:}")
    private String remoteApiKey;

    @Value("${llm.chat-model:gpt-4}")
    private String remoteModelId;

    @Value("${llm.chat.temperature:0.2}")
    private double remoteTemperature;

    private final LocalOpenAiClient openAiClient;
    private final Map<String, Long> unhealthyUntil = new ConcurrentHashMap<>();

    public LlmRouterService(LocalOpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    public String generateAnswer(String prompt, ChatContext ctx) {
        Objects.requireNonNull(prompt, "prompt");
        String session = (ctx != null && ctx.getSessionId() != null) ? ctx.getSessionId() : UUID.randomUUID().toString();

        // 1) Choose a healthy local endpoint
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(gemmaBaseUrl)) candidates.add(gemmaBaseUrl);
        if (StringUtils.hasText(qwenBaseUrl)) candidates.add(qwenBaseUrl);
        if (StringUtils.hasText(mistralBaseUrl)) candidates.add(mistralBaseUrl);
        if (StringUtils.hasText(lightBaseUrl)) candidates.add(lightBaseUrl);

        String chosenLocal = chooseHealthy(candidates);

        if (chosenLocal != null) {
            try {
                String json = openAiClient.chatCompletionBlocking(chosenLocal, null, localModelId, prompt, localTemperature, localTimeoutMs);
                String text = extractContent(json);
                markSuccess(chosenLocal);
                log.info("route session={} provider=local baseUrl={} model={} fallback={}", session, chosenLocal, localModelId, false);
                return text;
            } catch (Exception ex) {
                markFailure(chosenLocal);
                log.warn("local call failed baseUrl={} msg={}", chosenLocal, ex.toString());
                if (!fallbackEnabled) {
                    return "LLM error: " + ex.getMessage();
                }
            }
        } else {
            log.warn("no healthy local endpoints available");
            if (!fallbackEnabled) {
                return "LLM error: no healthy local endpoints";
            }
        }

        // 2) Fallback to remote
        try {
            String json = openAiClient.chatCompletionBlocking(remoteBaseUrl, remoteApiKey, normalizeModelId(remoteModelId), prompt, remoteTemperature, Math.max(15000L, localTimeoutMs));
            String text = extractContent(json);
            log.info("route session={} provider=remote baseUrl={} model={} fallback={}", session, remoteBaseUrl, remoteModelId, true);
            return text;
        } catch (Exception ex2) {
            log.error("fallback remote call failed baseUrl={} msg={}", remoteBaseUrl, ex2.toString());
            return "LLM error: " + ex2.getMessage();
        }
    }

    private String chooseHealthy(List<String> endpoints) {
        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>();
        for (String e : endpoints) {
            Long until = unhealthyUntil.get(e);
            if (until == null || until < now) healthy.add(e);
        }
        if (healthy.isEmpty()) return null;
        // simple rotation
        return healthy.get((int)(now % healthy.size()));
    }

    private void markFailure(String baseUrl) {
        if (baseUrl == null) return;
        unhealthyUntil.put(baseUrl, System.currentTimeMillis() + Math.max(5000L, cooldownMs));
    }

    private void markSuccess(String baseUrl) {
        if (baseUrl == null) return;
        unhealthyUntil.remove(baseUrl);
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
        String id = modelId.trim().toLowerCase();
        if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
        return modelId;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> m = om.readValue(json, Map.class);
            Object choices = m.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object first = ((List<?>) choices).get(0);
                if (first instanceof Map) {
                    Map<String, Object> c = (Map<String, Object>) first;
                    Object msg = c.get("message");
                    if (msg instanceof Map) {
                        Object content = ((Map<?, ?>) msg).get("content");
                        if (content != null) return String.valueOf(content);
                    }
                    Object text = c.get("text");
                    if (text != null) return String.valueOf(text);
                }
            }
        } catch (Exception ignore) {
        }
        return json;
    }


// --- GPT-5 Pro patch: weighted endpoint picker for TP mode ---
private String pickByWeight(java.util.List<Endpoint> endpoints) {
    double sum = 0.0;
    for (Endpoint e : endpoints) { sum += (e.weight <= 0 ? 0.0 : e.weight); }
    double r = Math.random() * (sum > 0 ? sum : 1.0);
    for (Endpoint e : endpoints) {
        double w = (e.weight <= 0 ? 0.0 : e.weight);
        if ((r -= w) <= 0) return e.url;
    }
    return endpoints.isEmpty() ? null : endpoints.get(0).url;
}
}
