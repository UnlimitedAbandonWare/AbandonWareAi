package com.example.lms.llm.gateway;

public record LlmRouteDecision(
        String requestedKey,
        String selectedKey,
        String fallbackKey,
        RoutingEligibility eligibility,
        boolean fallbackSelected,
        String reason,
        Integer outputLimit) {
    public LlmRouteDecision(String requestedKey, String selectedKey, String fallbackKey,
            RoutingEligibility eligibility, boolean fallbackSelected, String reason) {
        this(requestedKey, selectedKey, fallbackKey, eligibility, fallbackSelected, reason, null);
    }
}
