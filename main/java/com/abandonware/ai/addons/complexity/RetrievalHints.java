package com.abandonware.ai.addons.complexity;


public record RetrievalHints(
        int webTopK,
        int vectorTopK,
        boolean useCrossEncoder,
        boolean useBiEncoder,
        boolean enable2Pass,
        boolean enableWeb,
        String routingProfile
) {
    public RetrievalHints {
        webTopK = Math.max(0, webTopK);
        vectorTopK = Math.max(0, vectorTopK);
        routingProfile = routingProfile == null || routingProfile.isBlank() ? "default" : routingProfile;
    }

    public static RetrievalHints simple() {
        return new RetrievalHints(0, 8, false, true, false, false, "default");
    }
}
