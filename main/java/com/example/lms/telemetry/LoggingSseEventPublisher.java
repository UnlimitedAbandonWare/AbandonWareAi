package com.example.lms.telemetry;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Primary SSE publisher for ops telemetry.
 *
 * <p>Keeps a bounded in-memory replay buffer so diagnostics clients can observe
 * control breadcrumbs without coupling retrieval code to a servlet response.</p>
 */
@Primary
@Component
public class LoggingSseEventPublisher implements SseEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingSseEventPublisher.class);
    private static final int MAX_PAYLOAD_CHARS = 512;
    private static final int DEFAULT_REPLAY_LIMIT = 200;
    private static final int MAX_REPLAY_LIMIT = 10_000;

    private final Sinks.Many<Map<String, Object>> eventSink;

    public LoggingSseEventPublisher() {
        this(DEFAULT_REPLAY_LIMIT);
    }

    @Autowired
    public LoggingSseEventPublisher(@Value("${sse.replay-limit:200}") int replayLimit) {
        this.eventSink = Sinks.many().replay().limit(normalizeReplayLimit(replayLimit));
    }

    @Override
    public void emit(String type, Object payload) {
        emit(type, payload, null);
    }

    @Override
    public void emit(String type, Object payload, String sessionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        String safeType = SafeRedactor.traceLabelOrFallback(type, "event");
        String sessionHash = sessionHash(sessionId);
        event.put("ts", Instant.now().toString());
        event.put("type", safeType == null || safeType.isBlank() ? "event" : safeType);
        if (sessionHash != null) {
            event.put("sessionId", sessionHash);
        }
        event.put("payload", SafeRedactor.diagnosticValue("sseEvent", payload, MAX_PAYLOAD_CHARS));
        MlaBreadcrumb.appendSseEvent(String.valueOf(event.get("type")), event.get("payload"));

        Sinks.EmitResult result = eventSink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("[AWX][trace] SSE stream emit skipped result={}", result);
        }
        if (log.isDebugEnabled()) {
            log.debug("[AWX][trace] SSE[{}] -> {}", event.get("type"), event.get("payload"));
        }
    }

    @Override
    public Flux<Map<String, Object>> asStream() {
        return eventSink.asFlux();
    }

    @Override
    public Flux<Map<String, Object>> asStream(String sessionId) {
        String sessionHash = sessionHash(sessionId);
        if (sessionHash == null) {
            return asStream();
        }
        return eventSink.asFlux().filter(event -> sessionHash.equals(event.get("sessionId")));
    }

    private static String sessionHash(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return SafeRedactor.hashValue(sessionId.trim());
    }

    private static int normalizeReplayLimit(int replayLimit) {
        if (replayLimit < 1) {
            return DEFAULT_REPLAY_LIMIT;
        }
        return Math.min(replayLimit, MAX_REPLAY_LIMIT);
    }
}
