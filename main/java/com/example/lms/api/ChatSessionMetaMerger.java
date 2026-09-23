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

        if (uiReq.getModel() != null && !uiReq.getModel().isBlank()) {
            meta.put("model", uiReq.getModel());
        } else if (meta.containsKey("model")) {
            uiReq.setModel(String.valueOf(meta.get("model")));
        }

        if (uiReq.getSearchMode() != null) {
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

        return meta;
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
}
