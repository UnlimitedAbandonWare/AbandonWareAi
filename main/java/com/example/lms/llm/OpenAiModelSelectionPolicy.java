package com.example.lms.llm;

import com.example.lms.config.ConfigValueGuards;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class OpenAiModelSelectionPolicy {

    private static final String DEFAULT_OPENAI_CHAT_MODELS = String.join(",",
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.4-nano",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4o-mini",
            "o4-mini");

    private OpenAiModelSelectionPolicy() {
    }

    public static boolean effectiveAllowRemoteSelection(
            String llmProvider,
            boolean explicitAllowRemote,
            String openAiApiKey) {
        if (hasUsableOpenAiKey(openAiApiKey)) {
            return true;
        }
        return !isLocalProvider(llmProvider) && explicitAllowRemote;
    }

    public static boolean hasUsableOpenAiKey(String openAiApiKey) {
        return !ConfigValueGuards.isMissing(openAiApiKey);
    }

    public static List<String> openAiChatModels(String configuredCsv) {
        String raw = configuredCsv == null || configuredCsv.isBlank()
                ? DEFAULT_OPENAI_CHAT_MODELS
                : configuredCsv;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String model = ModelCapabilities.canonicalModelName(part);
            if (model == null) {
                continue;
            }
            model = model.trim();
            if (isSelectableOpenAiChatModel(model)) {
                out.add(model);
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean isConfiguredOpenAiChatModel(String modelId, String configuredCsv) {
        String canon = ModelCapabilities.canonicalModelName(modelId);
        if (canon == null || canon.isBlank()) {
            return false;
        }
        String target = canon.trim();
        for (String model : openAiChatModels(configuredCsv)) {
            if (model.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelectableOpenAiChatModel(String modelId) {
        String canon = ModelCapabilities.canonicalModelName(modelId);
        if (canon == null || canon.isBlank()) {
            return false;
        }
        String m = canon.trim().toLowerCase(Locale.ROOT);
        if (!ModelCapabilities.isRemoteLookingModelId(m)) {
            return false;
        }
        if (OpenAiEndpointCompatibility.isLikelyCompletionsOnlyModelId(m)) {
            return false;
        }
        return !isResponsesOnlyByDefault(m);
    }

    private static boolean isResponsesOnlyByDefault(String modelId) {
        return modelId.startsWith("gpt-5.5-pro")
                || modelId.startsWith("gpt-5-pro")
                || modelId.startsWith("gpt-5.1-codex")
                || modelId.startsWith("gpt-5-codex")
                || modelId.startsWith("o3-deep-research")
                || modelId.startsWith("o4-mini-deep-research");
    }

    private static boolean isLocalProvider(String llmProvider) {
        return "local".equalsIgnoreCase(llmProvider == null ? "" : llmProvider.trim());
    }
}
