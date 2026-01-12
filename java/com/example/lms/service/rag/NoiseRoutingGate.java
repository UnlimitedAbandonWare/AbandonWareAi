package com.example.lms.service.rag;

import com.example.lms.service.guard.GuardContext;
import java.util.Locale;
import java.util.Set;

/**
 * Heuristics to avoid routing obviously noisy prompts into expensive chains.
 *
 * <p>
 * This is intentionally lightweight and deterministic; it can be expanded
 * later.
 * </p>
 */
public final class NoiseRoutingGate {

    private static final Set<String> LOW_SIGNAL_PREFIXES = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "lol", "ok", "okay");

    private NoiseRoutingGate() {
    }

    /**
     * FQCN compatibility shim.
     *
     * <p>
     * Some codepaths use {@code com.example.lms.infra.resilience.NoiseRoutingGate}
     * while others import
     * {@code com.example.lms.service.rag.NoiseRoutingGate}. To avoid fragile
     * refactors, this overload
     * delegates to the infra implementation.
     * </p>
     */
    public static com.example.lms.infra.resilience.NoiseRoutingGate.GateDecision decideEscape(
            String gateKey,
            double escapeP,
            GuardContext ctx) {
        return com.example.lms.infra.resilience.NoiseRoutingGate.decideEscape(gateKey, escapeP, ctx);
    }

    public static Decision decideEscape(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return new Decision(true, "blank");
        }

        String lower = q.toLowerCase(Locale.ROOT);
        for (String p : LOW_SIGNAL_PREFIXES) {
            if (lower.equals(p) || lower.startsWith(p + " ")) {
                return new Decision(true, "low_signal_greeting");
            }
        }

        // Very short, mostly punctuation
        String alphaNum = lower.replaceAll("[^a-z0-9가-힣]", "");
        if (alphaNum.length() <= 2 && q.length() <= 6) {
            return new Decision(true, "too_short");
        }

        return new Decision(false, "ok");
    }

    public record Decision(boolean escape, String reason) {
    }
}
