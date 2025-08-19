package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;   // ✅ 1.0.1

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.example.lms.telemetry.SseEventPublisher;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {
    private final @Qualifier("mini") ChatModel mini;   // ✅ 타입 변경
    private final @Qualifier("high") ChatModel high;   // ✅ 타입 변경
    private final SseEventPublisher sse;

    public ChatModel route(RouteSignal sig) {          // ✅ 반환 타입 변경
        boolean upgrade =
                sig.complexity() > sig.gamma()
                        || sig.uncertainty() > sig.theta()
                        || (sig.intent() != null && sig.intent().isHighRisk())
                        || (sig.verbosity() != null && sig.verbosity().isDeepOrUltra())
                        || sig.maxTokens() >= 1536;

        String from = sig.preferred() == RouteSignal.Preference.HIGH ? "high" : "mini";
        String to = upgrade ? "high" : "mini";
        ChatModel chosen = upgrade ? high : mini;

        try {
            Map<String, Object> signalMap = sig.toSignalMap();
            sse.emit("MOE_ROUTE", new SseEventPublisher.Payload()
                    .kv("from", from)
                    .kv("to", to)
                    .kv("upgrade", upgrade)
                    .kv("reason", sig.reason())
                    .kv("signals", signalMap)
                    .build());
        } catch (Exception e) {
            log.debug("MOE_ROUTE SSE skipped: {}", e.toString());
        }
        return chosen;
    }
}

record RouteSignal(
        double complexity,
        double gamma,
        double uncertainty,
        double theta,
        Intent intent,
        Verbosity verbosity,
        int maxTokens,
        Preference preferred,
        String reason
) {
    /**
     * Convert this signal into a map representation suitable for logging or
     * SSE payloads.
     *
     * @return a map of signal names to values
     */
    public Map<String, Object> toSignalMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("complexity", complexity);
        m.put("gamma", gamma);
        m.put("uncertainty", uncertainty);
        m.put("theta", theta);
        m.put("intent", intent == null ? null : intent.name());
        m.put("verbosity", verbosity == null ? null : verbosity.name());
        m.put("maxTokens", maxTokens);
        m.put("preferred", preferred == null ? null : preferred.name());
        return m;
    }

    /**
     * Enumeration of model preference hints.  The caller can indicate a
     * preference for the high or mini model, though the router may still
     * upgrade based on other signals.
     */
    public enum Preference { HIGH, MINI }

    /**
     * Intent categories used by the router.  At minimum this enumeration
     * distinguishes high‑risk intents from general intents.  Additional
     * categories can be added as needed.
     */
    public enum Intent {
        GENERAL(false),
        HIGH_RISK(true);
        private final boolean highRisk;
        Intent(boolean highRisk) { this.highRisk = highRisk; }
        public boolean isHighRisk() { return highRisk; }
    }

    /**
     * Verbosity hints indicating how detailed a response should be.  DEEP and
     * ULTRA signal that richer, longer responses are expected and may warrant
     * an upgrade to a larger model.
     */
    public enum Verbosity {
        STANDARD,
        DEEP,
        ULTRA;
        public boolean isDeepOrUltra() {
            return this == DEEP || this == ULTRA;
        }
    }
}