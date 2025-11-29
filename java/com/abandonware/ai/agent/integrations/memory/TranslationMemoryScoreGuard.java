
package com.abandonware.ai.agent.integrations.memory;

public final class TranslationMemoryScoreGuard {
    private TranslationMemoryScoreGuard() {}
    public static double clampScore(Double v) {
        if (v == null) return 0.0d;
        double x = v.doubleValue();
        if (x < 0) return 0.0d;
        if (x > 1) return 1.0d;
        return x;
    }
}
