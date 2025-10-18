package com.example.lms.service.routing;

import java.util.HashMap;
import java.util.Map;



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
    public enum Intent { GENERAL, FACT, CODE, REWRITE, SEARCH_HEAVY }
    public enum Verbosity { TERSE, NORMAL, VERBOSE }
    public enum Preference { COST, BALANCED, QUALITY }

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