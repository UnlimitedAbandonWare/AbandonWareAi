package com.example.lms.llm.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RoutingEligibility(
        String routeKey,
        String provider,
        String model,
        String stage,
        int score,
        boolean eligible,
        boolean fallbackOnly,
        List<LlmFailureClass> failureClasses,
        Map<String, Object> safeMeta) {

    public RoutingEligibility {
        failureClasses = failureClasses == null ? List.of() : List.copyOf(failureClasses);
        safeMeta = safeMeta == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(safeMeta));
    }

    public static RoutingEligibility eligible(String routeKey, String provider, String model, String stage, int score,
            boolean fallbackOnly, Map<String, Object> meta) {
        return new RoutingEligibility(routeKey, provider, model, stage, score, true, fallbackOnly, List.of(), meta);
    }

    public static RoutingEligibility blocked(String routeKey, String provider, String model, String stage, int score,
            boolean fallbackOnly, List<LlmFailureClass> failures, Map<String, Object> meta) {
        return new RoutingEligibility(routeKey, provider, model, stage, score, false, fallbackOnly, failures, meta);
    }

    public LlmFailureClass primaryFailure() {
        return failureClasses.isEmpty() ? LlmFailureClass.NONE : failureClasses.get(0);
    }

    public Map<String, Object> asBreadcrumb() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("routeKey", routeKey);
        out.put("provider", provider);
        out.put("model", model);
        out.put("stage", stage);
        out.put("score", score);
        out.put("eligible", eligible);
        out.put("fallbackOnly", fallbackOnly);
        List<String> failures = new ArrayList<>();
        for (LlmFailureClass failureClass : failureClasses) {
            failures.add(failureClass.name());
        }
        out.put("failureClasses", failures);
        out.putAll(safeMeta);
        return out;
    }
}
