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
    public static RetrievalHints simple() {
        return new RetrievalHints(0, 8, false, true, false, false, "default");
    }
}