package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.service.SettingsService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class ChatRequestSettingsMerger {

    private ChatRequestSettingsMerger() {
    }

    static ChatRequestDto merge(
            ChatRequestDto ui,
            Map<String, String> settings,
            boolean defaultUseRag,
            Logger log) {
        Map<String, String> cfg = settings == null ? Map.of() : settings;
        Map<String, String> dirty = new HashMap<>();

        double temperature = firstNonNull(ui.getTemperature(), cfg.get(SettingsService.KEY_TEMPERATURE), 0.3);
        double topP = firstNonNull(ui.getTopP(), cfg.get(SettingsService.KEY_TOP_P), 1.0);
        double frequencyPenalty = firstNonNull(
                ui.getFrequencyPenalty(),
                cfg.get(SettingsService.KEY_FREQUENCY_PENALTY),
                0.0);
        double presencePenalty = firstNonNull(
                ui.getPresencePenalty(),
                cfg.get(SettingsService.KEY_PRESENCE_PENALTY),
                0.0);

        String model = Optional.ofNullable(ui.getModel()).filter(s -> !s.isBlank())
                .orElse(cfg.getOrDefault(SettingsService.KEY_OPENAI_MODEL, ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL));

        String effectiveModel = ModelCapabilities.canonicalModelName(model);
        double sanitizedTemperature = ModelCapabilities.sanitizeTemperature(effectiveModel, temperature);
        double sanitizedTopP = ModelCapabilities.sanitizeTopP(effectiveModel, topP);
        double sanitizedFrequencyPenalty = ModelCapabilities.sanitizeFrequencyPenalty(effectiveModel, frequencyPenalty);
        double sanitizedPresencePenalty = ModelCapabilities.sanitizePresencePenalty(effectiveModel, presencePenalty);

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
                .maxTokens(ui.getMaxTokens())
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
                .polish(ui.getPolish())
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
            if (defVal instanceof Number) {
                return (T) Double.valueOf(dbVal);
            }
            return (T) dbVal;
        }
        return defVal;
    }

    private static void trackChange(Map<String, String> cfg, String key, Object newVal, Map<String, String> dirty) {
        String nv = String.valueOf(newVal);
        if (!nv.equals(cfg.get(key))) {
            dirty.put(key, nv);
        }
    }
}
