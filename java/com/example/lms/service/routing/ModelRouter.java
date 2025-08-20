package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;   // ✅ 1.0.1

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.example.lms.telemetry.SseEventPublisher;

import java.util.Map;

@Slf4j
@Component("modelRouterCore")
@RequiredArgsConstructor
public class ModelRouter {
    /**
     * Miniature and high tier chat models are injected via qualifiers to
     * disambiguate multiple {@link ChatModel} beans.  The qualifier names
     * must match those defined in {@link com.example.lms.config.ModelConfig}.
     */
    private final @Qualifier("mini") ChatModel mini;
    private final @Qualifier("high") ChatModel high;
    private final SseEventPublisher sse;

    /**
     * Primary routing method that inspects a {@link RouteSignal} and
     * determines whether to upgrade from the mini to the high tier model.
     * The upgrade criteria mirror the heuristics documented in the
     * specification: upgrade when the complexity exceeds gamma, the
     * uncertainty exceeds theta, the intent is high‑risk, the verbosity
     * hints deep or ultra, or the requested token budget is at least
     * 1536.  The preferred field controls the initial model choice (mini
     * or high) before any upgrade rules are applied.
     *
     * @param sig composite signal describing the current request
     * @return the chosen chat model
     */
    public ChatModel route(RouteSignal sig) {
        boolean upgrade =
                sig.complexity() > sig.gamma()
                        || sig.uncertainty() > sig.theta()
                        || (sig.intent() != null && sig.intent().isHighRisk())
                        || (sig.verbosity() != null && sig.verbosity().isDeepOrUltra())
                        || sig.maxTokens() >= 1536
                        // Upgrade on evidence only when the initial draft was empty.
                        || (sig.evidencePresent() && sig.emptyDraft());

        String from = sig.preferred() == RouteSignal.Preference.HIGH ? "high" : "mini";
        String to = upgrade ? "high" : "mini";
        ChatModel chosen = upgrade ? high : mini;

        // emit SSE for observability; swallow any exceptions to avoid
        // impacting routing decisions if the telemetry layer is unavailable.
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

    /**
     * Convenience overload accepting only an intent string.  When invoked,
     * the intent is mapped to a {@link RouteSignal.Intent} while all other
     * numeric heuristics default to zero.  This method preserves
     * compatibility with legacy callers that routed solely on intent.
     *
     * @param intent intent hint (e.g. "GENERAL" or "HIGH_RISK")
     * @return the chosen chat model
     */
    public ChatModel route(String intent) {
        return route(intent, null, null, null);
    }

    /**
     * Convenience overload accepting high‑level hints (intent, risk level,
     * verbosity hint and maximum token budget) used by legacy services.
     * These hints are translated into a {@link RouteSignal} with
     * zeroed numeric heuristics.  RiskLevel supersedes intent when
     * determining high‑risk contexts.  Verbosity hints are mapped onto
     * the {@link RouteSignal.Verbosity} enum.  Preferred model is set
     * based on risk: if riskLevel is HIGH then the preferred model is
     * {@code HIGH}, otherwise it defaults to {@code MINI}.  The reason
     * field is left null.
     *
     * @param intent      high level intent string (may be null)
     * @param riskLevel   textual risk hint (e.g. "HIGH" for high risk)
     * @param verbosity   verbosity hint (brief, standard, deep or ultra)
     * @param maxTokens   maximum token budget (may be null)
     * @return the chosen chat model
     */
    public ChatModel route(String intent, String riskLevel, String verbosity, Integer maxTokens) {
        // Determine high‑risk status based on riskLevel
        boolean highRisk = riskLevel != null && "HIGH".equalsIgnoreCase(riskLevel);
        RouteSignal.Intent intentEnum = highRisk ? RouteSignal.Intent.HIGH_RISK : RouteSignal.Intent.GENERAL;
        // Attempt to map the provided intent string to a specific intent constant
        if (!highRisk && intent != null) {
            try {
                RouteSignal.Intent i = RouteSignal.Intent.valueOf(intent.trim().toUpperCase());
                intentEnum = i;
            } catch (IllegalArgumentException ex) {
                // fall back to previously determined intentEnum
            }
        }
        // Map verbosity hint to Verbosity enum; default to STANDARD
        RouteSignal.Verbosity verbosityEnum = RouteSignal.Verbosity.STANDARD;
        if (verbosity != null) {
            String v = verbosity.trim().toUpperCase();
            if ("DEEP".equals(v)) {
                verbosityEnum = RouteSignal.Verbosity.DEEP;
            } else if ("ULTRA".equals(v)) {
                verbosityEnum = RouteSignal.Verbosity.ULTRA;
            }
        }
        int tokens = (maxTokens != null) ? maxTokens : 0;
        RouteSignal.Preference pref = highRisk ? RouteSignal.Preference.HIGH : RouteSignal.Preference.MINI;
        // Construct a default signal with no evidence and no empty draft.  These flags
        // remain false for legacy calls where evidence-aware routing is not in play.
        RouteSignal sig = new RouteSignal(
                0.0,
                0.0,
                0.0,
                0.0,
                intentEnum,
                verbosityEnum,
                tokens,
                pref,
                null,
                false,
                false
        );
        return route(sig);
    }
}
