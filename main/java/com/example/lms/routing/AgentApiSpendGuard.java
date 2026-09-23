package com.example.lms.routing;

/**
 * Soft decisions for agent sessions. Production callers should ignore blocks
 * unless {@link ApiSpendAttribution#agentModeActive()} is true.
 */
public final class AgentApiSpendGuard {

    private AgentApiSpendGuard() {
    }

    public record Decision(boolean allow, String why, String cache) {
    }

    public static Decision beforeCall(
            String purpose,
            String provider,
            String model,
            String caller,
            String probeId,
            boolean explicitPaidOverride) {
        String fp = ApiSpendAttribution.fingerprint(purpose, provider, model, caller, probeId);
        if (!ApiSpendAttribution.agentModeActive()) {
            ApiSpendAttribution.record(purpose, provider, model, "n/a", "user_request", caller, "miss",
                    null, null, null, null, estimateTier(provider, model));
            return new Decision(true, "user_request", "miss");
        }
        if (ApiSpendAttribution.shouldSkipSuccessfulReplay(fp)) {
            ApiSpendAttribution.record(purpose, provider, model, "n/a", "verification_replay_blocked", caller,
                    "hit_skip", null, "skipped", null, null, "local0");
            return new Decision(false, "verification_replay_blocked", "hit_skip");
        }
        if (!explicitPaidOverride && ApiSpendAttribution.isStalePaidAutoModel(model)) {
            ApiSpendAttribution.record(purpose, provider, model, "paid_quality", "model_auto_blocked_stale", caller,
                    "forced", null, "blocked", null, null, "llm_paid");
            return new Decision(false, "model_auto_blocked_stale", "forced");
        }
        String why = "verification_required";
        if ("connectivity_min".equalsIgnoreCase(purpose) || "probe".equalsIgnoreCase(purpose)) {
            why = "connectivity_min";
        }
        ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), why, caller, "miss",
                null, null, null, null, estimateTier(provider, model));
        return new Decision(true, why, "miss");
    }

    public static void afterSuccess(String purpose, String provider, String model, String caller, String probeId,
                                    Integer promptTokens, Integer completionTokens, String estCostClass) {
        String fp = ApiSpendAttribution.fingerprint(purpose, provider, model, caller, probeId);
        ApiSpendAttribution.markSuccess(fp);
        ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), "verification_required",
                caller, "miss", 200, "ok", promptTokens, completionTokens, estCostClass);
    }

    public static void afterFailure(String purpose, String provider, String model, String caller, String probeId,
                                    Integer httpStatus, String errorClass) {
        ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), "verification_required",
                caller, "miss", httpStatus, errorClass, null, null, estimateTier(provider, model));
    }

    private static String estimateTier(String provider, String model) {
        String p = provider == null ? "" : provider.toLowerCase();
        if (p.contains("ollama") || p.contains("local")) {
            return "local0";
        }
        if (p.contains("brave") || p.contains("tavily") || p.contains("serp") || p.contains("naver")) {
            return "search";
        }
        if (p.contains("soniox") || p.contains("deepgram")) {
            return "stt";
        }
        if (ApiSpendAttribution.isStalePaidAutoModel(model) || p.contains("openai") || p.contains("anthropic")) {
            return "llm_paid";
        }
        return "llm_cheap";
    }
}
