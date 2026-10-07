package com.example.lms.api;

import com.example.lms.domain.ChatMessage;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ChatSessionDetailResponseBuilder {

    private static final String TRACE_META_PREFIX = "?TRACE?";
    private static final String TRACE_META_PREFIX_B64 = "?TRACE64?";
    private static final Pattern SAFE_MODEL_META = Pattern.compile("[A-Za-z0-9_.:/+@-]{1,80}");
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
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ChatMessage::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<ChatApiController.MessageDto> messages = new ArrayList<>();
        Map<Long, ChatApiController.TurnTraceDto> tracesByAssistant = new LinkedHashMap<>();
        Map<Long, String> snapshotIdsByAssistant = new LinkedHashMap<>();
        Map<Long, com.example.lms.dto.ChatStreamEvent.ExecutionModeSnapshot> executionModesByAssistant = new LinkedHashMap<>();
        Set<Long> ambiguousTraceOwners = new HashSet<>();
        Set<Long> assistantMessageIds = new HashSet<>();
        for (var message : raw) {
            if ("assistant".equals(message.getRole()) && message.getId() != null && message.getId() > 0L) {
                assistantMessageIds.add(message.getId());
            }
        }
        String lastModelMeta = null;
        Long legacyAssistantCandidate = null;
        boolean legacyAssistantAmbiguous = false;
        for (var m : raw) {
            String role = m.getRole();
            String content = m.getContent();

            if ("system".equals(role)) {
                if (content != null) {
                    String modelMeta = ChatModelMetaSupport.extractModelUsed(content);
                    if (modelMeta != null) {
                        lastModelMeta = modelMeta;
                        continue;
                    }
                    {
                        var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(content, m.getId());
                        if (pointer.isPresent()) {
                            Long assistantId = pointer.get().assistantMessageId();
                            if (exposeTrace && assistantId == null && pointer.get().legacyFallbackAllowed()
                                    && !legacyAssistantAmbiguous) {
                                assistantId = legacyAssistantCandidate;
                            }
                            if (assistantId != null && assistantMessageIds.contains(assistantId)
                                    && !ambiguousTraceOwners.contains(assistantId)) {
                                String existing = snapshotIdsByAssistant.get(assistantId);
                                if (existing != null && !existing.equals(pointer.get().snapshotId())) {
                                    tracesByAssistant.remove(assistantId);
                                    executionModesByAssistant.remove(assistantId);
                                    ambiguousTraceOwners.add(assistantId);
                                } else if (existing == null) {
                                    snapshotIdsByAssistant.put(assistantId, pointer.get().snapshotId());
                                    if (pointer.get().assistantMessageId() != null) {
                                        var mode = ChatStreamSignalBuilder.executionModeSnapshot(pointer.get().diagnostics());
                                        if (mode != null) executionModesByAssistant.put(assistantId, mode);
                                    }
                                    if (exposeTrace) tracesByAssistant.put(assistantId, new ChatApiController.TurnTraceDto(
                                            assistantId,
                                            pointer.get().snapshotId(),
                                            mergeModelMetaField(pointer.get().projection(), pointer.get().diagnostics(), lastModelMeta, session.getId())));
                                }
                            }
                            if (exposeTrace) lastModelMeta = null;
                        }
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

            if ("assistant".equals(role)) {
                if (m.getId() == null || m.getId() <= 0L || legacyAssistantCandidate != null) {
                    legacyAssistantAmbiguous = true;
                } else {
                    legacyAssistantCandidate = m.getId();
                }
            } else {
                legacyAssistantCandidate = null;
                legacyAssistantAmbiguous = false;
            }
            messages.add(new ChatApiController.MessageDto(m.getId(), role, content, m.getCreatedAt()));
        }

        messages.replaceAll(message -> "assistant".equals(message.role())
                && executionModesByAssistant.containsKey(message.turnId())
                ? new ChatApiController.MessageDto(message.turnId(), message.role(), message.content(),
                        message.timestamp(), executionModesByAssistant.get(message.turnId())) : message);

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
                savedSettings,
                List.copyOf(tracesByAssistant.values()));

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

    private static Map<String, String> mergeModelMetaField(Map<String, String> projection, Map<String, Object> diagnostics,
                                                        String modelMeta, Long sessionId) {
        Map<String, String> merged = new LinkedHashMap<>(projection);
        // These scalars have already passed the durable diagnostic allowlist and are bound to this answer.
        for (String key : List.of("observedModel", "prompt.citableEvidenceCount", "orch.mode")) {
            Object value = diagnostics.get(key);
            if (value != null) merged.put(key, String.valueOf(value));
        }
        if (modelMeta != null && !modelMeta.isBlank() && SAFE_MODEL_META.matcher(modelMeta).matches()) {
            merged.put("modelUsed", modelMeta);
        }
        // Only assistant-bound envelopes can use the session-owned detail endpoint.
        if (sessionId != null && sessionId > 0 && projection.containsKey("assistantMessageId")) {
            merged.put("sessionId", String.valueOf(sessionId));
        }
        return Map.copyOf(merged);
    }
}
