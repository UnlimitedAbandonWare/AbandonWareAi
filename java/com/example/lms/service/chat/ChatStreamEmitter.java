package com.example.lms.service.chat;

import com.example.lms.dto.answer.AnswerUnderstanding;
import com.example.lms.dto.ChatStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple registry and emitter for server‑sent events.  The chat controller
 * registers a sink per session so that interceptors can emit additional
 * events asynchronously (e.g. the understanding summary) without direct
 * reference to the HTTP response.  Sinks are unregistered once the chat
 * completes to avoid memory leaks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamEmitter {

    private final Map<String, Sinks.Many<ServerSentEvent<ChatStreamEvent>>> sinks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Register an SSE sink for a given session.  This should be called at the
     * start of a streaming request.  Any existing sink under the same key
     * will be replaced.
     *
     * @param sessionKey normalized session identifier (e.g. "chat-123")
     * @param sink       the sink to register
     */
    public void registerSink(String sessionKey, Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) {
        if (sessionKey == null || sink == null) return;
        sinks.put(sessionKey, sink);
    }

    /**
     * Remove and close the sink for the given session.  Call this when the
     * streaming request completes or is cancelled.
     *
     * @param sessionKey the session identifier
     */
    public void unregisterSink(String sessionKey) {
        if (sessionKey == null) return;
        sinks.remove(sessionKey);
    }

    /**
     * Emit an understanding summary over SSE to the registered sink for the
     * session.  The summary is serialized to JSON and included in the
     * {@link ChatStreamEvent} data field.  If no sink is registered this
     * method silently returns.
     *
     * @param sessionKey the session identifier
     * @param summary    the structured summary to emit
     */
    public void emitUnderstanding(String sessionKey, AnswerUnderstanding summary) {
        if (sessionKey == null || summary == null) return;
        var sink = sinks.get(sessionKey);
        if (sink == null) return;
        try {
            String json = objectMapper.writeValueAsString(summary);
            ChatStreamEvent event = ChatStreamEvent.understanding(json);
            sink.tryEmitNext(ServerSentEvent.<ChatStreamEvent>builder(event)
                    .event(event.type())
                    .build());
        } catch (JsonProcessingException e) {
            log.warn("[Emitter] Failed to serialize understanding: {}", e.toString());
        }
    }
}