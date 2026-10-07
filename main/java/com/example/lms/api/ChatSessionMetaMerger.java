package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ChatSessionMetaMerger {

    private ChatSessionMetaMerger() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(
            ObjectMapper objectMapper,
            ChatSession session,
            ChatRequestDto uiReq,
            Logger log) {
        Map<String, Object> meta = new HashMap<>();

        String rawMeta = session.getSessionMeta();
        if (rawMeta != null && !rawMeta.isBlank()) {
            try {
                meta.putAll(objectMapper.readValue(rawMeta, Map.class));
            } catch (Exception e) {
                String safeErrorType = errorType(e);
                TraceStore.put("chat.sessionMeta.suppressed.stage", "parse");
                TraceStore.put("chat.sessionMeta.suppressed.errorType", safeErrorType);
                TraceStore.put("chat.sessionMeta.suppressed.parse", true);
                TraceStore.put("chat.sessionMeta.suppressed.parse.errorType", safeErrorType);
                log.warn("Failed to parse session_meta for session {}: errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(session.getId())),
                        SafeRedactor.hashValue(e.getMessage()),
                        e.getMessage() == null ? 0 : e.getMessage().length());
            }
        }

        // Sampling is conversation state too. Omission restores; explicit zero wins.
        if (uiReq.getExecutionMode() != null) meta.put("executionMode", uiReq.getExecutionMode().name());
        else if (meta.get("executionMode") instanceof String value) {
            try { uiReq.setExecutionMode(com.example.lms.domain.enums.ExecutionMode.valueOf(value)); }
            catch (IllegalArgumentException invalid) { traceSuppressedMergeSkipped(log, "execution_mode_restore", session, invalid); }
        }
        inheritPreference(meta, "customInstructions", uiReq.getCustomInstructions(), uiReq::setCustomInstructions);
        inheritPreference(meta, "responseTone", uiReq.getResponseTone(), uiReq::setResponseTone);
        inheritPreference(meta, "responseLength", uiReq.getResponseLength(), uiReq::setResponseLength);
        inheritPreference(meta, "responseLanguage", uiReq.getResponseLanguage(), uiReq::setResponseLanguage);
        inheritNumber(meta, "temperature", uiReq.getTemperature(), uiReq::setTemperature);
        inheritNumber(meta, "topP", uiReq.getTopP(), uiReq::setTopP);
        inheritNumber(meta, "frequencyPenalty", uiReq.getFrequencyPenalty(), uiReq::setFrequencyPenalty);
        inheritNumber(meta, "presencePenalty", uiReq.getPresencePenalty(), uiReq::setPresencePenalty);
        if (uiReq.getMaxTokens() != null) meta.put("maxTokens", uiReq.getMaxTokens());
        else if (meta.get("maxTokens") instanceof Number value && value.intValue() > 0
                && value.doubleValue() == value.intValue()) uiReq.setMaxTokens(value.intValue());
        if (uiReq.getRagAnswerPolicy() != null) meta.put("ragAnswerPolicy", uiReq.getRagAnswerPolicy());
        else if (meta.get("ragAnswerPolicy") instanceof String value
                && java.util.Set.of("adaptive", "evidence_only").contains(value)) uiReq.setRagAnswerPolicy(value);

        if (uiReq.getModelSelectionMode() != null) meta.put("modelSelectionMode", uiReq.getModelSelectionMode());
        else if (uiReq.getStrictModelSelection() != null) meta.put("modelSelectionMode", uiReq.isStrictModelSelection() ? "strict" : "preferred");
        else if (uiReq.getModel() == null && meta.get("modelSelectionMode") instanceof String value
                && java.util.Set.of("preferred", "strict", "auto").contains(value)) {
            uiReq.setModelSelectionMode(value);
            uiReq.setStrictModelSelection("strict".equals(value));
        }

        if (uiReq.getModel() != null && !uiReq.getModel().isBlank()) {
            meta.put("model", uiReq.getModel());
        } else if (meta.containsKey("model")) {
            uiReq.setModel(String.valueOf(meta.get("model")));
        }

        if (uiReq.isSearchModeExplicit()) {
            meta.put("searchMode", uiReq.getSearchMode().name());
        } else if (meta.containsKey("searchMode")) {
            try {
                String searchMode = textOrNull(meta.get("searchMode"));
                if (searchMode != null) {
                    uiReq.setSearchMode(SearchMode.valueOf(searchMode.toUpperCase(Locale.ROOT)));
                }
            } catch (Exception error) {
                traceSuppressedMergeSkipped(log, "search_mode_restore", session, error);
            }
        }

        if (uiReq.getUseRag() != null) {
            meta.put("useRag", uiReq.getUseRag());
        } else if (meta.containsKey("useRag")) {
            Object v = meta.get("useRag");
            if (v instanceof Boolean b) {
                uiReq.setUseRag(b);
            } else if (v != null) {
                uiReq.setUseRag(Boolean.parseBoolean(String.valueOf(v)));
            }
        }

        if (uiReq.getUseWebSearch() != null) {
            meta.put("useWebSearch", uiReq.getUseWebSearch());
        } else if (meta.containsKey("useWebSearch")) {
            Object v = meta.get("useWebSearch");
            if (v instanceof Boolean b) {
                uiReq.setUseWebSearch(b);
            } else if (v != null) {
                uiReq.setUseWebSearch(Boolean.parseBoolean(String.valueOf(v)));
            }
        }

        if (uiReq.getGoogleSearchRescueEnabled() != null) {
            meta.put("googleSearchRescueEnabled", uiReq.getGoogleSearchRescueEnabled());
        } else if (meta.get("googleSearchRescueEnabled") instanceof Boolean enabled) {
            uiReq.setGoogleSearchRescueEnabled(enabled);
        }

        if (uiReq.getPrecisionSearch() != null) {
            meta.put("precisionSearch", uiReq.getPrecisionSearch());
        } else if (meta.containsKey("precisionSearch")) {
            Object v = meta.get("precisionSearch");
            if (v instanceof Boolean b) {
                uiReq.setPrecisionSearch(b);
            } else if (v != null) {
                uiReq.setPrecisionSearch(Boolean.parseBoolean(String.valueOf(v)));
            }
        }

        if (uiReq.getSearchScopes() != null && !uiReq.getSearchScopes().isEmpty()) {
            meta.put("searchScopes", uiReq.getSearchScopes());
        } else if (meta.containsKey("searchScopes")) {
            try {
                uiReq.setSearchScopes((List<String>) meta.get("searchScopes"));
            } catch (Exception error) {
                traceSuppressedMergeSkipped(log, "search_scopes_restore", session, error);
            }
        }

        // Omission inherits the session choice; explicit values keep MemoryMode's own fallback semantics.
        if (uiReq.getMemoryMode() != null) {
            meta.put("memoryMode", uiReq.getMemoryMode());
        } else if (meta.get("memoryMode") instanceof String memoryMode) {
            uiReq.setMemoryMode(memoryMode);
        }

        if (uiReq.getProfile() != null && !uiReq.getProfile().isBlank()) {
            meta.put("profile", uiReq.getProfile());
        } else if (meta.containsKey("profile")) {
            Object v = meta.get("profile");
            if (v != null) {
                uiReq.setProfile(String.valueOf(v));
            }
        }

        if (uiReq.getGuardLevel() != null && !uiReq.getGuardLevel().isBlank()) {
            meta.put("guardLevel", uiReq.getGuardLevel());
        } else if (meta.containsKey("guardLevel")) {
            Object v = meta.get("guardLevel");
            if (v != null) {
                uiReq.setGuardLevel(String.valueOf(v));
            }
        }

        // Session settings document version; unknown legacy keys are preserved above.
        meta.put("schemaVersion", 1);
        return meta;
    }

    private static void inheritNumber(Map<String, Object> meta, String key, Double request,
            java.util.function.Consumer<Double> restore) {
        if (request != null) meta.put(key, request);
        else if (meta.get(key) instanceof Number number && Double.isFinite(number.doubleValue()))
            restore.accept(number.doubleValue());
    }

    private static void traceSuppressedMergeSkipped(Logger log, String stage, ChatSession session, Exception error) {
        if (log == null) {
            return;
        }
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(error);
        TraceStore.put("chat.sessionMeta.suppressed.stage", safeStage);
        TraceStore.put("chat.sessionMeta.suppressed.errorType", safeErrorType);
        TraceStore.put("chat.sessionMeta.suppressed." + safeStage, true);
        TraceStore.put("chat.sessionMeta.suppressed." + safeStage + ".errorType", safeErrorType);
        String sessionId = session == null ? "" : String.valueOf(session.getId());
        log.debug("Session meta merge skipped stage={} sessionHash={} sessionLength={} errorType={}",
                safeStage,
                SafeRedactor.hashValue(sessionId),
                sessionId.length(),
                safeErrorType);
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }

    private static String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static void inheritPreference(Map<String, Object> meta, String key, String value,
            java.util.function.Consumer<String> restore) {
        Object candidate = value != null ? value : meta.get(key);
        if (candidate instanceof String text) {
            // Validate saved data with the same contract as a current request before restoring it.
            com.example.lms.service.ChatPreferenceService.validate(Map.of(key, text));
            if (value != null) meta.put(key, text); else restore.accept(text);
        }
    }
}
