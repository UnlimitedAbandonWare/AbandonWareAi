
package ai.abandonware.nova.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted + health-aware router for multi vLLM endpoints (light/mistral/gemma).
 * Chooses among Light(8002), Mistral(8001), and Gemma(8000) by weight when healthy.
 * On failure, mark unhealthy and fail over; retry unhealthy after cooldownMillis.
 */
@Service
public class LlmRouterService {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);

    @Value("${llmrouter.models.gemma.base-url:http://localhost:11434}")
    private String gemmaUrl;

    @Value("${llmrouter.models.gemma.weight:0.7}")
    private double gemmaWeight;

    @Value("${llmrouter.models.mistral.base-url:http://localhost:8001}")
    private String mistralUrl;

    @Value("${llmrouter.models.mistral.weight:0.3}")
    private double mistralWeight;

    @Value("${llmrouter.models.light.base-url:http://localhost:8002}")
    private String lightUrl;

    @Value("${llmrouter.models.light.weight:0.45}")
    private double lightWeight;


    @Value("${llmrouter.cooldown-ms:20000}")
    private long cooldownMillis;

    private final AtomicBoolean gemmaHealthy = new AtomicBoolean(true);
    private final AtomicBoolean mistralHealthy = new AtomicBoolean(true);
    private final AtomicLong gemmaLastFailedAt = new AtomicLong(0L);
    private final AtomicLong mistralLastFailedAt = new AtomicLong(0L);

    private final AtomicBoolean lightHealthy = new AtomicBoolean(true);
    private final AtomicLong lightLastFailedAt = new AtomicLong(0L);


    public String chooseBaseUrl(String sessionId) {
        long now = System.currentTimeMillis();

        // Cooldown recovery
        if (!gemmaHealthy.get() && now - gemmaLastFailedAt.get() >= cooldownMillis) {
            gemmaHealthy.set(true);
            log.info("Router: retry enabling Gemma after cooldown.");
        }
        if (!mistralHealthy.get() && now - mistralLastFailedAt.get() >= cooldownMillis) {
            mistralHealthy.set(true);
            log.info("Router: retry enabling Mistral after cooldown.");
        }
        if (!lightHealthy.get() && now - lightLastFailedAt.get() >= cooldownMillis) {
            lightHealthy.set(true);
            log.info("Router: retry enabling Light after cooldown.");
        }

        // Build weighted pool of healthy models
        double total = 0.0;
        java.util.List<String> urls = new java.util.ArrayList<>();
        java.util.List<Double> weights = new java.util.ArrayList<>();

        if (gemmaHealthy.get()) { urls.add(gemmaUrl); weights.add(gemmaWeight); total += gemmaWeight; }
        if (mistralHealthy.get()) { urls.add(mistralUrl); weights.add(mistralWeight); total += mistralWeight; }
        if (lightHealthy.get()) { urls.add(lightUrl); weights.add(lightWeight); total += lightWeight; }

        if (urls.isEmpty()) {
            // All unhealthy: route to the one with oldest failure or default to light
            long g = now - gemmaLastFailedAt.get();
            long m2 = now - mistralLastFailedAt.get();
            long l = now - lightLastFailedAt.get();
            if (g >= m2 && g >= l) return gemmaUrl;
            if (m2 >= g && m2 >= l) return mistralUrl;
            return lightUrl;
        }

        double r = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0.0;
        for (int i2 = 0; i2 < urls.size(); i2++) {
            acc += weights.get(i2);
            if (r <= acc) {
                return urls.get(i2);
            }
        }
        return urls.get(urls.size() - 1);
    }

    public String altBaseUrl(String chosen) {
        if (chosen == null) return gemmaUrl;
        return chosen.equals(gemmaUrl) ? mistralUrl : gemmaUrl;
    }

    public void markFailure(String baseUrl) {
        long now = System.currentTimeMillis();
        if (baseUrl != null && baseUrl.equals(gemmaUrl)) {
            gemmaHealthy.set(false);
            gemmaLastFailedAt.set(now);
            log.warn("Router: mark Gemma unhealthy for {} ms", cooldownMillis);
        } else if (baseUrl != null && baseUrl.equals(mistralUrl)) {
            mistralHealthy.set(false);
            mistralLastFailedAt.set(now);
            log.warn("Router: mark Mistral unhealthy for {} ms", cooldownMillis);
        }
    
        else if (baseUrl != null && baseUrl.equals(lightUrl)) {
            lightHealthy.set(false);
            lightLastFailedAt.set(System.currentTimeMillis());
            log.warn("Router: mark Light unhealthy for {} ms", cooldownMillis);
        }
}

    public void markSuccess(String baseUrl) {
        if (baseUrl != null && baseUrl.equals(gemmaUrl)) {
            gemmaHealthy.set(true);
        } else if (baseUrl != null && baseUrl.equals(mistralUrl)) {
            mistralHealthy.set(true);
        }
    
        else if (baseUrl != null && baseUrl.equals(lightUrl)) {
            lightHealthy.set(true);
        }
}


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    return modelId;
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
