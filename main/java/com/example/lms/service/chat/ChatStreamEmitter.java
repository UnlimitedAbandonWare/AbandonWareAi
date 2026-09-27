package com.example.lms.service.chat;

import com.example.lms.dto.answer.AnswerUnderstanding;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Simple registry and emitter for server-sent events.  The chat controller
 * registers a sink per session so that interceptors can emit additional
 * events asynchronously (e.g. the understanding summary) without direct
 * reference to the HTTP response.  Sinks are unregistered once the chat
 * completes to avoid memory leaks.
 */
@Component
@RequiredArgsConstructor
public class ChatStreamEmitter {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamEmitter.class);

    private final Map<String, Sinks.Many<ServerSentEvent<ChatStreamEvent>>> sinks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Register the producer sink for one exact run. */
    public boolean registerSink(ChatRunExecutionContext context,
                                Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) {
        return context != null
                && sink != null
                && context.registerProducerSink(sink);
    }

    /** Remove only the exact producer mapping installed by this run. */
    public boolean unregisterSink(ChatRunExecutionContext context,
                                  Sinks.Many<ServerSentEvent<ChatStreamEvent>> expectedSink) {
        return context != null
                && expectedSink != null
                && context.unregisterProducerSink(expectedSink);
    }

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

    /** Conditional legacy cleanup; late owners cannot remove a replacement sink. */
    public boolean unregisterSink(
            String sessionKey,
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> expectedSink) {
        return sessionKey != null
                && expectedSink != null
                && sinks.remove(sessionKey, expectedSink);
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
        emitUnderstanding(sink, summary);
    }

    public void emitUnderstanding(ChatRunExecutionContext context, AnswerUnderstanding summary) {
        if (context == null || summary == null) return;
        try {
            String json = objectMapper.writeValueAsString(summary);
            ChatStreamEvent event = ChatStreamEvent.understanding(json);
            context.emitToProducer(ServerSentEvent.<ChatStreamEvent>builder(event)
                    .event(event.type())
                    .build());
        } catch (JsonProcessingException e) {
            log.warn("[Emitter] Failed to serialize understanding. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void emitUnderstanding(Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
                                   AnswerUnderstanding summary) {
        try {
            String json = objectMapper.writeValueAsString(summary);
            ChatStreamEvent event = ChatStreamEvent.understanding(json);
            Sinks.EmitResult emitResult = sink.tryEmitNext(ServerSentEvent.<ChatStreamEvent>builder(event)
                    .event(event.type())
                    .build());
            if (emitResult.isFailure()) {
                TraceStore.inc("chat.stream.emitter.failure.count");
                TraceStore.put("chat.stream.emitter.failure.reason", emitResult.name().toLowerCase(java.util.Locale.ROOT));
                TraceStore.put("chat.stream.emitter.failure.stage", "understanding");
            }
        } catch (JsonProcessingException e) {
            log.warn("[Emitter] Failed to serialize understanding. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    public void sendToken(String sessionKey, String text) {
        send(sessionKey, com.example.lms.dto.ChatStreamEvent.token(text));
    }
    public void sendStatus(String sessionKey, String text) {
        send(sessionKey, com.example.lms.dto.ChatStreamEvent.status(text));
    }

    public void sendToken(ChatRunExecutionContext context, String text) {
        send(context, com.example.lms.dto.ChatStreamEvent.token(text));
    }

    public void sendStatus(ChatRunExecutionContext context, String text) {
        send(context, com.example.lms.dto.ChatStreamEvent.status(text));
    }

    /**
     * Emit a generic event to the registered SSE sink.  If no sink exists for
     * the given session or either parameter is null the call is a no-op.
     *
     * @param sessionKey session identifier to emit to
     * @param event the stream event to send
     */
    private void send(String sessionKey, com.example.lms.dto.ChatStreamEvent event) {
        if (sessionKey == null || event == null) return;
        var sink = sinks.get(sessionKey);
        if (sink == null) return;
        try {
            Sinks.EmitResult emitResult = sink.tryEmitNext(ServerSentEvent.<com.example.lms.dto.ChatStreamEvent>builder(event)
                    .event(event.type())
                    .build());
            if (emitResult.isFailure()) {
                TraceStore.inc("chat.stream.emitter.failure.count");
                TraceStore.put("chat.stream.emitter.failure.reason", emitResult.name().toLowerCase(java.util.Locale.ROOT));
                TraceStore.put("chat.stream.emitter.failure.stage", "send");
            }
        } catch (Exception e) {
            log.warn("[Emitter] Failed to send SSE event. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void send(ChatRunExecutionContext context, com.example.lms.dto.ChatStreamEvent event) {
        if (context == null || event == null) return;
        try {
            context.emitToProducer(ServerSentEvent.<com.example.lms.dto.ChatStreamEvent>builder(event)
                    .event(event.type())
                    .build());
        } catch (Exception e) {
            log.warn("[Emitter] Failed to send exact-run SSE event. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
    
}
