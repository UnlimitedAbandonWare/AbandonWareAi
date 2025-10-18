package com.example.lms.service.routing;


/**
 * Predefined {@link RouteSignal} presets for common escalation scenarios.  The
 * values chosen here are heuristics tailored for location oriented tasks and
 * may be adjusted as needed.  Callers should use these helpers when
 * requesting an escalation through {@link ModelRouter#escalate(RouteSignal)}.
 */
public final class RouteSignalPresets {
    private RouteSignalPresets() {}

    /**
     * Return a signal suitable for summarising a user's current area.  This
     * preset increases complexity and uncertainty compared to normal chat
     * interactions and prefers higher quality models.  It sets the maximum
     * output token budget to 800 tokens.
     *
     * @return a {@link RouteSignal} configured for area briefing
     */
    public static RouteSignal forAreaBrief() {
        return new RouteSignal(
                0.7,        // complexity
                0.6,        // gamma
                0.8,        // uncertainty
                0.5,        // theta
                RouteSignal.Intent.GENERAL,
                RouteSignal.Verbosity.NORMAL,
                800,
                RouteSignal.Preference.QUALITY,
                "area-brief"
        );
    }
}