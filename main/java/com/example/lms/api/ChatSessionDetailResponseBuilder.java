package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.service.SettingsService;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ChatSessionDetailResponseBuilder {

    private static final String TRACE_META_PREFIX = "?TRACE?";
    private static final String TRACE_META_PREFIX_B64 = "?TRACE64?";
    private static final String EXPOSE_HEADERS =
            "X-Model-Used,X-RAG-Used,X-User,X-Session-Owner,X-Session-Id,X-Request-Id,X-Trace-Snapshot-Id";

    private ChatSessionDetailResponseBuilder() {
    }

    @SuppressWarnings("unchecked")
    static ResponseEntity<ChatApiController.SessionDetail> build(
            ChatSession session,
            String username,
            ObjectMapper objectMapper,
            Map<String, String> settings,
            boolean exposeTrace,
            Logger log) {
        var raw = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .sorted(Comparator.comparing(m -> m.getCreatedAt()))
                .toList();

        List<ChatApiController.MessageDto> messages = new ArrayList<>();
        for (var m : raw) {
            String role = m.getRole();
            String content = m.getContent();

            if ("system".equals(role)) {
                if (content != null) {
                    if (ChatModelMetaSupport.extractModelUsed(content) != null) {
                        continue;
                    }
                    Optional<ChatApiController.MessageDto> traceMeta =
                            ChatTraceMetaMessageRestorer.restore(m.getId(), content, m.getCreatedAt(), exposeTrace);
                    if (traceMeta.isPresent()) {
                        messages.add(traceMeta.get());
                        continue;
                    }
                    if (content.startsWith(TRACE_META_PREFIX) || content.startsWith(TRACE_META_PREFIX_B64)) {
                        continue;
                    }
                }
                continue;
            }

            messages.add(new ChatApiController.MessageDto(m.getId(), role, content, m.getCreatedAt()));
        }

        Map<String, Object> savedSettings = Collections.emptyMap();
        String meta = session.getSessionMeta();
        if (meta != null && !meta.isBlank()) {
            try {
                savedSettings = objectMapper.readValue(meta, Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse session_meta for session {}: errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(session.getId())),
                        SafeRedactor.hashValue(e.getMessage()),
                        e.getMessage() == null ? 0 : e.getMessage().length());
            }
        }

        String modelUsed = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(m -> ChatModelMetaSupport.extractModelUsed(m.getContent()))
                .filter(Objects::nonNull)
                .reduce((p, c) -> c)
                .orElse(null);
        String effectiveModel;
        if (modelUsed == null || modelUsed.isBlank() || ChatModelMetaSupport.isWrapperLabel(modelUsed)) {
            String cfgModel = settings == null ? null : settings.get(SettingsService.KEY_OPENAI_MODEL);
            effectiveModel = (cfgModel != null && !cfgModel.isBlank())
                    ? cfgModel
                    : ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
        } else {
            effectiveModel = modelUsed;
        }

        ChatApiController.SessionDetail detail = new ChatApiController.SessionDetail(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                messages,
                effectiveModel,
                savedSettings);

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        ok.header("X-Model-Used", effectiveModel);
        String owner = Optional.ofNullable(session.getAdministrator())
                .map(com.example.lms.domain.Administrator::getUsername)
                .orElse(username != null ? username : "anonymousUser");
        ok.header("X-Session-Owner", owner);
        ok.header("X-User", owner);
        ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        return ok.body(detail);
    }
}
