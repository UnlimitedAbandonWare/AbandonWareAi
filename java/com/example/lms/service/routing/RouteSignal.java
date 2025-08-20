package com.example.lms.service.routing;

import java.util.HashMap;
import java.util.Map;

/**
 * Top-level definition of the {@code RouteSignal} record used to convey
 * composite routing hints to the {@link ModelRouter}.  Each field
 * represents a dimension of the request that influences model selection.
 *
 * <p>Fields such as complexity, uncertainty and verbosity correspond
 * directly to heuristics documented in the service specification.  The
 * {@link #toSignalMap()} method produces a map suitable for logging or
 * SSE payloads.</p>
 */
public record RouteSignal(
        double complexity,
        double gamma,
        double uncertainty,
        double theta,
        Intent intent,
        Verbosity verbosity,
        int maxTokens,
        Preference preferred,
        String reason,
        boolean evidencePresent,
        /**
         * Indicates whether the initial draft returned by the mini model lacked
         * substantive information.  When both {@code evidencePresent} and
         * {@code emptyDraft} are true the router will upgrade to the high tier
         * model to attempt regeneration.  This field defaults to {@code false}
         * when not explicitly specified.
         */
        boolean emptyDraft
) {

    /**
     * Convert this signal into a map representation suitable for logging or
     * SSE payloads.
     *
     * @return a map of signal names to values
     */
    public Map<String, Object> toSignalMap() {
        // Use a HashMap since ordering is not significant in the emitted payload.
        // Represent null values explicitly so downstream consumers can
        // differentiate between absent and unspecified fields.
        Map<String, Object> m = new HashMap<>();
        m.put("complexity", complexity);
        m.put("gamma", gamma);
        m.put("uncertainty", uncertainty);
        m.put("theta", theta);
        m.put("intent", intent == null ? null : intent.name());
        m.put("verbosity", verbosity == null ? null : verbosity.name());
        m.put("maxTokens", maxTokens);
        m.put("preferred", preferred == null ? null : preferred.name());
        m.put("reason", reason);
        m.put("evidencePresent", evidencePresent);
        m.put("emptyDraft", emptyDraft);
        return m;
    }

    /**
     * Enumeration of model preference hints.  The caller can indicate a
     * preference for the high or mini model, though the router may still
     * upgrade based on other signals.
     */
    /**
     * Preference for a particular model tier.  The MINI model is the default
     * lightweight model, while HIGH refers to the larger, more capable model.
     */
    public enum Preference {
        HIGH,
        MINI
    }

    /**
     * Intent categories used by the router.  At minimum this enumeration
     * distinguishes high‑risk intents from general intents.  Additional
     * categories can be added as needed.
     */
    /**
     * Intent categories used to guide the router.  Certain intents are
     * considered high‑risk or high‑tier and should trigger an upgrade to
     * the larger model.  Each constant is annotated with a boolean flag
     * indicating whether it should be treated as high risk.  This design
     * retains backward compatibility with older routing logic that
     * interrogates {@link #isHighRisk()}.
     */
    public enum Intent {
        GENERAL(false),
        HIGH_RISK(true),
        PAIRING(true),
        RECOMMENDATION(true),
        EXPLANATION(true),
        TUTORIAL(true),
        ANALYSIS(true);

        private final boolean highRisk;

        Intent(boolean highRisk) {
            this.highRisk = highRisk;
        }

        /**
         * Indicates whether this intent should be treated as high risk.  High
         * risk intents cause the router to favour the high tier model when
         * making decisions.
         *
         * @return true if the intent is high risk, false otherwise
         */
        public boolean isHighRisk() {
            return highRisk;
        }
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