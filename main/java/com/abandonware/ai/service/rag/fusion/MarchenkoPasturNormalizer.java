package com.abandonware.ai.service.rag.fusion;

/**
 * Minimal Marchenko-Pastur style soft clamp.
 * In absence of per-source variance statistics, this acts as a safe identity-ish clamp to [0,1.5],
 * which can be tightened once empirical sigma^2 and q=n/m are logged via Soak.
 */
final class MarchenkoPasturNormalizer {
    double clamp(double s) {
        // Soft clamp: negative to 0, overly large to 1.5
        if (Double.isNaN(s)) return 0.0;
        if (s < 0) return 0.0;
        if (s > 1.5) return 1.5;
        return s;
    }
}