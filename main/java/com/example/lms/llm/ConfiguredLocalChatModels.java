package com.example.lms.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfiguredLocalChatModels {
    public static final String DEFAULT_CSV = "gemma4:26b,smtek/Qwen3.8-27B:Q3_K_XL,qwen3-vl:8b,qwen3.5:9b,gemma4:12b";

    private ConfiguredLocalChatModels() {
    }

    public static List<String> parse(String csv, String... additionalModelIds) {
        Set<String> models = new LinkedHashSet<>();
        addCsv(models, csv);
        if (additionalModelIds != null) {
            for (String modelId : additionalModelIds) {
                addOne(models, modelId);
            }
        }
        return new ArrayList<>(models);
    }

    public static boolean contains(String csv, String modelId, String... additionalModelIds) {
        String target = canonicalLocalChatModel(modelId);
        if (target == null) {
            return false;
        }
        for (String configured : parse(csv, additionalModelIds)) {
            if (target.equals(configured.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void addCsv(Set<String> models, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String part : csv.split(",")) {
            addOne(models, part);
        }
    }

    private static void addOne(Set<String> models, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        String canonical = ModelCapabilities.normalizeLocalModelAlias(modelId);
        if (canonical == null || canonical.isBlank()) {
            return;
        }
        if (!ModelCapabilities.isLocalChatModelId(canonical)) {
            return;
        }
        models.add(canonical.trim());
    }

    private static String canonicalLocalChatModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        String canonical = ModelCapabilities.normalizeLocalModelAlias(modelId);
        if (canonical == null || canonical.isBlank()) {
            return null;
        }
        if (!ModelCapabilities.isLocalChatModelId(canonical)) {
            return null;
        }
        return canonical.trim().toLowerCase(Locale.ROOT);
    }
}
