package com.example.lms.llm;

import java.net.URI;
import java.util.Set;

/** Exact Chat Completions sampling contracts verified against OpenAI docs on 2026-10-03. */
public final class OpenAiSamplingContract {
    public enum Action { SEND, OMIT, LEGACY }

    public record Policy(Action temperature, Action topP) {}

    private static final Set<String> INITIAL_MODELS = Set.of("gpt-5", "gpt-5-mini", "gpt-5-nano");
    private static final Set<String> NONE_DEFAULT_MODELS = Set.of("gpt-5.1", "gpt-5.2");
    private static final Policy SEND = new Policy(Action.SEND, Action.SEND);
    private static final Policy OMIT = new Policy(Action.OMIT, Action.OMIT);
    private static final Policy LEGACY = new Policy(Action.LEGACY, Action.LEGACY);

    private OpenAiSamplingContract() {}

    /** Preserve explicit preferences until the endpoint and final effort can decide SEND or OMIT. */
    public static boolean defersMergerClamp(String modelId) {
        return modelId != null && (INITIAL_MODELS.contains(modelId) || NONE_DEFAULT_MODELS.contains(modelId));
    }

    /** A missing effort uses the model default; this decision never changes the transmitted effort. */
    public static Policy resolve(String baseUrl, String modelId, String reasoningEffort) {
        if (!isOfficialEndpoint(baseUrl) || modelId == null) return LEGACY;
        if (INITIAL_MODELS.contains(modelId)) return OMIT;
        if (NONE_DEFAULT_MODELS.contains(modelId)) {
            String effectiveEffort = reasoningEffort == null ? "none" : reasoningEffort;
            return "none".equals(effectiveEffort) ? SEND : OMIT;
        }
        // No prefix inference for dated, chat, codex, pro, or unverified model IDs.
        return LEGACY;
    }

    public static boolean isOfficialEndpoint(String baseUrl) {
        if (baseUrl == null) return false;
        try {
            URI uri = URI.create(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "api.openai.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("/v1".equals(uri.getRawPath()) || "/v1/".equals(uri.getRawPath()));
        } catch (IllegalArgumentException invalidEndpoint) {
            return false;
        }
    }
}
