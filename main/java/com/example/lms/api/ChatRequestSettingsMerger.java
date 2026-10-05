package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.service.SettingsService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ChatRequestSettingsMerger {

    private ChatRequestSettingsMerger() {
    }

    /** One precedence path shared by the chat boundary and the settings descriptor. */
    public static ResolvedChatSettings resolve(ChatRequestDto ui, Map<String, Object> user,
            Map<String, Object> admin, com.example.lms.config.ChatDefaultsProperties factory, Logger log) {
        Map<String, Object> input = requestValues(ui);
        Map<String, Object> original = ui.getChatSettingsSnapshot() == null ? input : ui.getChatSettingsSnapshot().request();
        Map<String, Object> values = new java.util.LinkedHashMap<>(factory.values());
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        values.keySet().forEach(key -> sources.put(key, "FACTORY"));
        for (var tier : java.util.List.of(admin, user)) {
            if (tier == null) continue;
            for (var entry : tier.entrySet()) if (values.containsKey(entry.getKey()) && entry.getValue() != null) {
                values.put(entry.getKey(), entry.getValue());
                sources.put(entry.getKey(), tier == user ? "USER" : "ADMIN_DB");
            }
            normalizePairs(values, tier, sources, tier == user ? "USER" : "ADMIN_DB");
        }
        for (var entry : input.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
            sources.put(entry.getKey(), original.containsKey(entry.getKey()) ? "REQUEST" : "SESSION");
        }
        Map<String, Object> pairIntent = new java.util.LinkedHashMap<>(input);
        for (var pair : java.util.List.of(java.util.List.of("model", "modelSelectionMode"),
                java.util.List.of("searchMode", "useWebSearch"))) {
            if (pair.stream().anyMatch(original::containsKey)) {
                pair.forEach(pairIntent::remove);
                for (String key : pair) if (original.containsKey(key)) pairIntent.put(key, original.get(key));
            }
        }
        normalizePairs(values, pairIntent, sources, original.containsKey("model") || original.containsKey("modelSelectionMode")
                ? "REQUEST" : "SESSION");
        Map<String, Object> valid = com.example.lms.service.ChatPreferenceService.validate(values);
        ChatRequestDto normalized = ui.toBuilder().model((String) valid.get("model"))
                .modelSelectionMode((String) valid.get("modelSelectionMode"))
                .executionMode(com.example.lms.domain.enums.ExecutionMode.valueOf((String) valid.get("executionMode")))
                .strictModelSelection("strict".equals(valid.get("modelSelectionMode")))
                .temperature(((Number) valid.get("temperature")).doubleValue())
                .topP(((Number) valid.get("topP")).doubleValue())
                .frequencyPenalty(((Number) valid.get("frequencyPenalty")).doubleValue())
                .presencePenalty(((Number) valid.get("presencePenalty")).doubleValue())
                .maxTokens(((Number) valid.get("maxTokens")).intValue())
                .useRag((Boolean) valid.get("useRag")).useWebSearch((Boolean) valid.get("useWebSearch"))
                .searchMode(com.example.lms.gptsearch.dto.SearchMode.valueOf((String) valid.get("searchMode")))
                .ragAnswerPolicy((String) valid.get("ragAnswerPolicy"))
                .customInstructions((String) valid.get("customInstructions"))
                .responseTone((String) valid.get("responseTone"))
                .responseLength((String) valid.get("responseLength"))
                .responseLanguage((String) valid.get("responseLanguage"))
                .memoryMode((String) valid.get("memoryMode"))
                .retrievalRequestIntent(ui.getRetrievalRequestIntent() != null ? ui.getRetrievalRequestIntent()
                        : new ChatRequestDto.RetrievalRequestIntent(ui.getUseWebSearch(), ui.getUseRag()))
                .build();
        ChatRequestDto effective = merge(normalized, Map.of(), factory.getUseRag(), log,
                !"FACTORY".equals(sources.get("temperature")),
                !"FACTORY".equals(sources.get("topP")));
        return new ResolvedChatSettings(effective, Map.copyOf(requestValues(effective)), Map.copyOf(sources));
    }

    private static void normalizePairs(Map<String, Object> values, Map<String, Object> tier,
            Map<String, String> sources, String source) {
        if (tier.containsKey("model") || tier.containsKey("modelSelectionMode")) {
            String model = String.valueOf(values.get("model"));
            boolean logicalAuto = java.util.Set.of("llmrouter.auto", "auto").contains(model);
            String mode = tier.containsKey("modelSelectionMode") ? String.valueOf(tier.get("modelSelectionMode"))
                    : logicalAuto ? "auto" : "preferred";
            if (logicalAuto && "strict".equals(mode)) throw new IllegalArgumentException("strict_requires_concrete_model");
            if ("auto".equals(mode)) values.put("model", "llmrouter.auto");
            values.put("modelSelectionMode", mode);
            sources.put("model", source); sources.put("modelSelectionMode", source);
        }
        if (tier.containsKey("searchMode") || tier.containsKey("useWebSearch")) {
            String mode = tier.containsKey("searchMode") ? String.valueOf(tier.get("searchMode"))
                    : Boolean.TRUE.equals(tier.get("useWebSearch")) ? "AUTO" : "OFF";
            if (Boolean.FALSE.equals(tier.get("useWebSearch"))) mode = "OFF";
            values.put("searchMode", mode); values.put("useWebSearch", !"OFF".equals(mode));
            // A restored web pair retains SESSION attribution; explicit request flags remain REQUEST.
            String pairSource = sources.getOrDefault(tier.containsKey("searchMode") ? "searchMode" : "useWebSearch", source);
            sources.put("searchMode", pairSource); sources.put("useWebSearch", pairSource);
        }
    }

    public static Map<String, Object> requestValues(ChatRequestDto ui) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        put(values, "model", ui.getModel());
        put(values, "executionMode", ui.getExecutionMode() == null ? null : ui.getExecutionMode().name());
        put(values, "modelSelectionMode", ui.getModelSelectionMode() != null ? ui.getModelSelectionMode()
                : ui.getStrictModelSelection() != null ? ui.isStrictModelSelection() ? "strict" : "preferred" : null);
        put(values, "temperature", ui.getTemperature()); put(values, "topP", ui.getTopP());
        put(values, "frequencyPenalty", ui.getFrequencyPenalty()); put(values, "presencePenalty", ui.getPresencePenalty());
        put(values, "maxTokens", ui.getMaxTokens()); put(values, "useRag", ui.getUseRag());
        put(values, "useWebSearch", ui.getUseWebSearch());
        if (ui.isSearchModeExplicit()) values.put("searchMode", ui.getSearchMode().name());
        put(values, "ragAnswerPolicy", ui.getRagAnswerPolicy());
        // An explicit empty instruction clears the session value rather than inheriting it.
        if (ui.getCustomInstructions() != null) values.put("customInstructions", ui.getCustomInstructions());
        put(values, "responseTone", ui.getResponseTone()); put(values, "responseLength", ui.getResponseLength());
        put(values, "responseLanguage", ui.getResponseLanguage());
        if (ui.getMemoryMode() != null) values.put("memoryMode",
                com.example.lms.domain.enums.MemoryMode.fromString(ui.getMemoryMode()).name().toLowerCase(java.util.Locale.ROOT));
        return Map.copyOf(values);
    }
    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) values.put(key, value);
    }


    static ChatRequestDto merge(
            ChatRequestDto ui,
            Map<String, String> settings,
            boolean defaultUseRag,
            Logger log) {
        return merge(ui, settings, defaultUseRag, log,
                ui.getTemperature() != null || settings != null && settings.containsKey(SettingsService.KEY_TEMPERATURE),
                ui.getTopP() != null || settings != null && settings.containsKey(SettingsService.KEY_TOP_P));
    }

    private static ChatRequestDto merge(
            ChatRequestDto ui, Map<String, String> settings, boolean defaultUseRag, Logger log,
            boolean temperaturePreferencePresent, boolean topPPreferencePresent) {
        Map<String, String> cfg = settings == null ? Map.of() : settings;
        Map<String, String> dirty = new HashMap<>();

        boolean[] warned = {false};
        double temperature = numericSetting(ui.getTemperature(), cfg, SettingsService.KEY_TEMPERATURE, 0.3, log, warned);
        double topP = numericSetting(ui.getTopP(), cfg, SettingsService.KEY_TOP_P, 1.0, log, warned);
        double frequencyPenalty = numericSetting(
                ui.getFrequencyPenalty(),
                cfg, SettingsService.KEY_FREQUENCY_PENALTY,
                0.0, log, warned);
        double presencePenalty = numericSetting(
                ui.getPresencePenalty(),
                cfg, SettingsService.KEY_PRESENCE_PENALTY,
                0.0, log, warned);

        String model = Optional.ofNullable(ui.getModel()).filter(s -> !s.isBlank())
                .orElse(cfg.getOrDefault(SettingsService.KEY_OPENAI_MODEL, ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL));

        String effectiveModel = ModelCapabilities.canonicalModelName(model);
        // Preserve preferences until endpoint/final-effort policy runs, retaining omitted-value defaults.
        boolean supportedPreferences = com.example.lms.llm.OpenAiSamplingContract.defersMergerClamp(effectiveModel);
        boolean deferTemperature = supportedPreferences && temperaturePreferencePresent
                && (ui.getTemperature() != null || cfg.get(SettingsService.KEY_TEMPERATURE) != null
                && SettingsService.numericValidationError(Map.of(SettingsService.KEY_TEMPERATURE,
                        cfg.get(SettingsService.KEY_TEMPERATURE))) == null);
        boolean deferTopP = supportedPreferences && topPPreferencePresent
                && (ui.getTopP() != null || cfg.get(SettingsService.KEY_TOP_P) != null
                && SettingsService.numericValidationError(Map.of(SettingsService.KEY_TOP_P,
                        cfg.get(SettingsService.KEY_TOP_P))) == null);
        double sanitizedTemperature = ModelCapabilities.sanitizeTemperature(
                deferTemperature ? null : effectiveModel, temperature);
        double sanitizedTopP = ModelCapabilities.sanitizeTopP(
                deferTopP ? null : effectiveModel, topP);
        double sanitizedFrequencyPenalty = ModelCapabilities.sanitizeFrequencyPenalty(effectiveModel, frequencyPenalty);
        double sanitizedPresencePenalty = ModelCapabilities.sanitizePresencePenalty(effectiveModel, presencePenalty);

        if (deferTemperature || deferTopP) {
            log.debug("Sampling deferred to OpenAiSamplingContract for modelHash={} modelLength={}",
                    SafeRedactor.hashValue(effectiveModel),
                    effectiveModel == null ? 0 : effectiveModel.length());
        }
        if (Double.compare(temperature, sanitizedTemperature) != 0) {
            log.debug("Adjusted temperature {} -> {} for modelHash={} modelLength={}",
                    temperature,
                    sanitizedTemperature,
                    SafeRedactor.hashValue(effectiveModel),
                    effectiveModel == null ? 0 : effectiveModel.length());
            temperature = sanitizedTemperature;
        }
        if (Double.compare(topP, sanitizedTopP) != 0) {
            log.debug("Adjusted top_p {} -> {} for modelHash={} modelLength={}",
                    topP,
                    sanitizedTopP,
                    SafeRedactor.hashValue(effectiveModel),
                    effectiveModel == null ? 0 : effectiveModel.length());
            topP = sanitizedTopP;
        }
        if (Double.compare(frequencyPenalty, sanitizedFrequencyPenalty) != 0) {
            log.debug("Adjusted frequency_penalty {} -> {} for modelHash={} modelLength={}",
                    frequencyPenalty,
                    sanitizedFrequencyPenalty,
                    SafeRedactor.hashValue(effectiveModel),
                    effectiveModel == null ? 0 : effectiveModel.length());
            frequencyPenalty = sanitizedFrequencyPenalty;
        }
        if (Double.compare(presencePenalty, sanitizedPresencePenalty) != 0) {
            log.debug("Adjusted presence_penalty {} -> {} for modelHash={} modelLength={}",
                    presencePenalty,
                    sanitizedPresencePenalty,
                    SafeRedactor.hashValue(effectiveModel),
                    effectiveModel == null ? 0 : effectiveModel.length());
            presencePenalty = sanitizedPresencePenalty;
        }

        trackChange(cfg, SettingsService.KEY_TEMPERATURE, temperature, dirty);
        trackChange(cfg, SettingsService.KEY_TOP_P, topP, dirty);
        trackChange(cfg, SettingsService.KEY_FREQUENCY_PENALTY, frequencyPenalty, dirty);
        trackChange(cfg, SettingsService.KEY_PRESENCE_PENALTY, presencePenalty, dirty);

        ChatRequestDto.RetrievalRequestIntent retrievalIntent = ui.getRetrievalRequestIntent() != null
                ? ui.getRetrievalRequestIntent()
                : new ChatRequestDto.RetrievalRequestIntent(ui.getUseWebSearch(), ui.getUseRag());
        Boolean normUseRag = ui.getUseRag() != null ? ui.getUseRag() : defaultUseRag;
        Boolean normUseWeb;
        if (ui.getUseWebSearch() != null) {
            normUseWeb = ui.getUseWebSearch();
        } else {
            String cfgVal = cfg.getOrDefault("chat.defaults.useWebSearch", "false");
            normUseWeb = Boolean.valueOf(cfgVal);
        }
        return ui.toBuilder()
                .sessionId(ui.getSessionId())
                .message(ui.getMessage())
                .history(ui.getHistory())
                .mode(ui.getMode())
                .memoryMode(ui.getMemoryMode())
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .maxTokens(ui.getMaxTokens() != null ? ui.getMaxTokens() : 2048)
                .useVerification(ui.getUseVerification())
                .useRag(normUseRag)
                .useWebSearch(normUseWeb)
                .understandingEnabled(ui.isUnderstandingEnabled())
                .searchMode(ui.getSearchMode())
                .webProviders(ui.getWebProviders())
                .officialSourcesOnly(ui.getOfficialSourcesOnly())
                .webTopK(ui.getWebTopK())
                .precisionSearch(ui.getPrecisionSearch())
                .precisionTopK(ui.getPrecisionTopK())
                .accumulation(ui.getAccumulation())
                .roleScope(ui.getRoleScope())
                .domainProfile(ui.getDomainProfile())
                .attachmentIds(ui.getAttachmentIds())
                .attachmentGraphConsent(ui.getAttachmentGraphConsent())
                .contextPreparationRequested(ui.isContextPreparationRequested())
                .polish(ui.getPolish())
                .ragAnswerPolicy(ui.getRagAnswerPolicy() != null
                        ? ui.getRagAnswerPolicy()
                        : cfg.getOrDefault("chat.ragAnswerPolicy", "adaptive"))
                .webSearchExplicit(ui.getWebSearchExplicit())
                .retrievalRequestIntent(retrievalIntent)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> T firstNonNull(T uiVal, String dbVal, T defVal) {
        if (uiVal != null) {
            return uiVal;
        }
        if (dbVal != null) {
            try {
                String value = dbVal.trim();
                if (defVal instanceof Integer) return (T) Integer.valueOf(value);
                if (defVal instanceof Long) return (T) Long.valueOf(value);
                if (defVal instanceof Short) return (T) Short.valueOf(value);
                if (defVal instanceof Byte) return (T) Byte.valueOf(value);
                if (defVal instanceof Float) {
                    Float number = Float.valueOf(value);
                    return Float.isFinite(number) ? (T) number : defVal;
                }
                if (defVal instanceof Double) {
                    Double number = Double.valueOf(value);
                    return Double.isFinite(number) ? (T) number : defVal;
                }
            } catch (NumberFormatException invalid) {
                return defVal;
            }
            return (T) dbVal;
        }
        return defVal;
    }

    private static double numericSetting(Double ui, Map<String, String> cfg, String key,
            double fallback, Logger log, boolean[] warned) {
        if (ui != null) return ui;
        String stored = cfg.get(key);
        if (stored == null) return fallback;
        SettingsService.InvalidNumericSetting invalid =
                SettingsService.numericValidationError(Map.of(key, stored));
        if (invalid != null) {
            if (!warned[0]) {
                log.warn("Stored numeric setting invalid key={} reason={}; using defaults", key, invalid.reason());
                warned[0] = true;
            }
            return fallback;
        }
        return firstNonNull(null, stored, fallback);
    }

    private static void trackChange(Map<String, String> cfg, String key, Object newVal, Map<String, String> dirty) {
        String nv = String.valueOf(newVal);
        if (!nv.equals(cfg.get(key))) {
            dirty.put(key, nv);
        }
    }
}
