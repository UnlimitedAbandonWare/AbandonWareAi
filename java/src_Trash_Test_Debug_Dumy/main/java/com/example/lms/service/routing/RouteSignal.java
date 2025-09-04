package com.example.lms.service.routing;

import java.util.HashMap;
import java.util.Map;

/**
 * Routing signal record capturing various heuristics used by the
 * {@link ModelRouter} to choose between different chat models.  The
 * complexity, gamma, uncertainty and theta fields represent arbitrary
 * numeric measures computed by upstream components.  Optional intent,
 * verbosity and preferred fields allow callers to express qualitative
 * preferences.  When creating new signals it is recommended to use the
 * constructors provided rather than directly referencing the record
 * canonical constructor.
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
        String reason
) {

    /**
     * Enumerated intents.  These values describe the high‑level goal of
     * the request and may be used by the router to bias model selection.
     */
    public enum Intent {
        ANSWER,
        SUMMARIZE,
        SEARCH,
        TRANSLATE,
        CODE,
        IMAGE,
        OTHER,
        /**
         * Backward‑compat default/chatty intent.  Some callers previously used
         * {@code GENERAL} to indicate no specific intent; alias it here to
         * prevent IllegalArgumentExceptions during enum deserialisation.
         */
        GENERAL
    }
    /**
     * Verbosity hints passed to the router.  Implementations may choose
     * between terse, normal or detailed outputs based on this hint.
     */
    public enum Verbosity { TERSE, NORMAL, DETAILED }
    /**
     * Preferred trade‑off between speed and quality.  Balanced is the
     * default when no strong preference is specified.
     */
    public enum Preference {
        SPEED,
        QUALITY,
        BALANCED,
        /**
         * Backward‑compat alias for callers expecting a "cost‑priority" flag.
         */
        COST
    }

    /**
     * Convenience constructor absorbing unknown fields.  Provides default
     * values for intent, verbosity, maxTokens and preferred.  The reason
     * parameter may be null.
     */
    public RouteSignal(double complexity, double gamma, double uncertainty, double theta) {
        this(complexity, gamma, uncertainty, theta, null, null, 2048, null, null);
    }

    /**
     * Convenience constructor with a custom reason.  Other optional fields
     * are defaulted to reasonable values.
     */
    public RouteSignal(double complexity, double gamma, double uncertainty, double theta, String reason) {
        this(complexity, gamma, uncertainty, theta, null, null, 2048, null, reason);
    }

    /**
     * Convert the signal to a map.  Primarily used for logging and
     * prompt context generation.  Null fields are preserved as null
     * entries in the returned map.
     *
     * @return a mutable map representation of this signal
     */
    public Map<String, Object> toSignalMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("complexity", complexity);
        m.put("gamma", gamma);
        m.put("uncertainty", uncertainty);
        m.put("theta", theta);
        m.put("intent", intent != null ? intent.name() : null);
        m.put("verbosity", verbosity != null ? verbosity.name() : null);
        m.put("maxTokens", maxTokens);
        m.put("preferred", preferred != null ? preferred.name() : null);
        m.put("reason", reason);
        return m;
    }
}