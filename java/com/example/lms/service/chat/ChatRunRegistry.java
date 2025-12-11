package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;




/**
 * Registry for tracking in-flight chat runs.  Each chat session can have at most
 * one active run; subsequent subscribers can attach to the existing run and
 * replay buffered events.  Upon completion or cancellation the run is evicted
 * after a configurable TTL.  The underlying sink uses a replay buffer to
 * deliver all previously emitted events to late subscribers.
 */
@Component
@RequiredArgsConstructor
public class ChatRunRegistry {

    /** Status of a chat run. */
    public enum Status { RUNNING, DONE, CANCELLED }

    /** Holder for per-session run state. */
    static final class Run {
        final String runId = UUID.randomUUID().toString();
        final Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink;
        volatile Status status = Status.RUNNING;
        volatile Instant started = Instant.now();
        volatile Instant lastEvent = Instant.now();
        Run(Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink) { this.sink = sink; }
    }

    /** In-memory map of session IDs to active runs. */
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    /** Capacity of the replay buffer when replaying past events on reattach. */
    @Value("${chat.resume.replay-capacity:512}")
    int replayCapacity;

    /** Time-to-live for completed or cancelled runs (in seconds). */
    @Value("${chat.resume.ttl-seconds:300}")
    int ttlSeconds;

    /**
     * Start a new run for the given session ID or return the existing sink if one
     * already exists.  The sink is configured with a bounded replay buffer.
     *
     * @param sessionId chat session identifier
     * @return sink associated with the session
     */
    public Sinks.Many<ServerSentEvent<ChatStreamEvent>> startOrGet(Long sessionId) {
        return runs.computeIfAbsent(sessionId, id ->
                new Run(Sinks.many().replay().limit(replayCapacity))).sink;
    }

    /**
     * Whether the given session currently has an active run.
     *
     * @param sessionId session identifier
     * @return true if running, false otherwise
     */
    public boolean isRunning(Long sessionId) {
        Run r = runs.get(sessionId);
        return r != null && r.status == Status.RUNNING;
    }

    /**
     * Attach to an existing run.  Returns an empty flux if no run exists.
     *
     * @param sessionId session identifier
     * @return flux of server-sent events
     */
    public Flux<ServerSentEvent<ChatStreamEvent>> attach(Long sessionId) {
        Run r = runs.get(sessionId);
        return (r == null) ? Flux.empty() : r.sink.asFlux();
    }

    /**
     * Update the last event timestamp for the run associated with the session.  This
     * can be used to implement custom inactivity timeouts if needed.
     *
     * @param sessionId session identifier
     */
    public void touch(Long sessionId) {
        Run r = runs.get(sessionId);
        if (r != null) r.lastEvent = Instant.now();
    }

    /**
     * Mark a run as completed and schedule its eviction after the TTL expires.
     *
     * @param sessionId session identifier
     */
    public void markDone(Long sessionId) {
        Run r = runs.get(sessionId);
        if (r != null) r.status = Status.DONE;
        scheduleEvict(sessionId);
    }

    /**
     * Mark a run as cancelled and schedule its eviction after the TTL expires.
     *
     * @param sessionId session identifier
     */
    public void markCancelled(Long sessionId) {
        Run r = runs.get(sessionId);
        if (r != null) r.status = Status.CANCELLED;
        scheduleEvict(sessionId);
    }

    private void scheduleEvict(Long sessionId) {
        new Thread(() -> {
            try { Thread.sleep(ttlSeconds * 1000L); } catch (InterruptedException ignored) {}
            runs.remove(sessionId);
        }, "run-ttl-" + sessionId).start();
    }
}