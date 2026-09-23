package com.example.lms.uaw.autolearn;

import java.util.Locale;
import java.util.Set;

final class ExternalFreeModelPolicy {

    static final String CURATE_ONLY_REASON = "external_free_model_curate_only";
    static final String PRIVACY_BLOCK_REASON = "external_privacy_block";

    private static final String DEFAULT_ROUTE_MODEL = "llmrouter.external";
    private static final String DEFAULT_PROVIDER_HOST = "opencode.ai";
    private static final Set<String> OPENCODE_FREE_CHAT_MODELS = Set.of(
            "deepseek-v4-flash-free",
            "mimo-v2.5-free",
            "nemotron-3-super-free",
            "big-pickle");

    private ExternalFreeModelPolicy() {
    }

    static Evaluation evaluate(UawAutolearnProperties.ExternalQuota cfg,
                               String requestedModel,
                               String modelUsed,
                               String provider) {
        UawAutolearnProperties.ExternalQuota effective = cfg == null
                ? new UawAutolearnProperties.ExternalQuota()
                : cfg;
        if (policyDisabled(effective.getCanonicalTrainingPolicy())) {
            return new Evaluation(Decision.DISABLED, "policy_disabled", endpointFamily(modelUsed), "DISABLED");
        }
        if (isExternalRoute(effective, requestedModel)
                || isOpenCodeProvider(provider)
                || isConfiguredFreeModel(effective, modelUsed)
                || isKnownOpenCodeFreeModel(modelUsed)) {
            return new Evaluation(Decision.CURATE_ONLY, CURATE_ONLY_REASON,
                    endpointFamily(firstNonBlank(modelUsed, effective.getFreeModel())),
                    "CURATE_ONLY");
        }
        return new Evaluation(Decision.TRAIN_ALLOWED, "", endpointFamily(modelUsed), "TRAIN_ALLOWED");
    }

    static boolean isExternalRoute(UawAutolearnProperties.ExternalQuota cfg, String requestedModel) {
        String routeModel = trimToEmpty(cfg == null ? null : cfg.getRouteModel());
        if (routeModel.isBlank()) {
            routeModel = DEFAULT_ROUTE_MODEL;
        }
        return trimToEmpty(requestedModel).equalsIgnoreCase(routeModel);
    }

    static boolean isStaticSyntheticOnly(UawAutolearnProperties.ExternalQuota cfg) {
        return "STATIC_SYNTHETIC_ONLY".equalsIgnoreCase(trimToEmpty(cfg == null ? null : cfg.getPrivacyMode()));
    }

    static String endpointFamily(String model) {
        String normalized = normalizeModelId(model);
        if (normalized.isBlank()) {
            return "UNKNOWN";
        }
        if (OPENCODE_FREE_CHAT_MODELS.contains(normalized)
                || normalized.startsWith("minimax-")
                || normalized.startsWith("glm-")
                || normalized.startsWith("kimi-")
                || normalized.startsWith("grok-build-")) {
            return "CHAT_COMPLETIONS";
        }
        if (normalized.startsWith("gpt-")) {
            return "RESPONSES";
        }
        if (normalized.startsWith("claude-") || normalized.startsWith("qwen")) {
            return "MESSAGES";
        }
        if (normalized.startsWith("gemini-")) {
            return "MODEL_SPECIFIC";
        }
        return "UNKNOWN";
    }

    static boolean hostMatches(String endpointHost, String providerHost) {
        String endpoint = trimToEmpty(endpointHost).toLowerCase(Locale.ROOT);
        String provider = trimToEmpty(providerHost).toLowerCase(Locale.ROOT);
        if (provider.isBlank()) {
            provider = DEFAULT_PROVIDER_HOST;
        }
        return endpoint.equals(provider) || endpoint.endsWith("." + provider);
    }

    private static boolean isConfiguredFreeModel(UawAutolearnProperties.ExternalQuota cfg, String modelUsed) {
        return !trimToEmpty(cfg == null ? null : cfg.getFreeModel()).isBlank()
                && normalizeModelId(modelUsed).equals(normalizeModelId(cfg.getFreeModel()));
    }

    private static boolean isKnownOpenCodeFreeModel(String modelUsed) {
        return OPENCODE_FREE_CHAT_MODELS.contains(normalizeModelId(modelUsed));
    }

    private static boolean isOpenCodeProvider(String provider) {
        return "opencode".equalsIgnoreCase(trimToEmpty(provider));
    }

    private static boolean policyDisabled(String policy) {
        String normalized = trimToEmpty(policy);
        return "DISABLED".equalsIgnoreCase(normalized) || "OFF".equalsIgnoreCase(normalized);
    }

    private static String normalizeModelId(String value) {
        String normalized = trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("opencode/")) {
            normalized = normalized.substring("opencode/".length());
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String fallback) {
        String a = trimToEmpty(first);
        return a.isBlank() ? trimToEmpty(fallback) : a;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    enum Decision {
        TRAIN_ALLOWED,
        CURATE_ONLY,
        DISABLED
    }

    record Evaluation(Decision decision, String disabledReason, String endpointFamily, String modelPolicy) {
        boolean curateOnly() {
            return decision == Decision.CURATE_ONLY;
        }
    }
}
