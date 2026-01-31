package com.example.lms.service.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;




/**
 * Coordinates streaming sessions for Server-Sent Events (SSE) and long-running
 * tasks.  This component centralises the lifecycle management of
 * cancellation flags and cleanup hooks previously scattered throughout
 * {@code ChatService}.  When a client requests cancellation the
 * corresponding flag is set and may be polled by downstream tasks to
 * gracefully terminate their work.
 */
@Component
public class StreamingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StreamingCoordinator.class);

    /** Map of session identifiers to cancellation flags. */
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /**
     * Register a new streaming session.  A session is identified by a
     * monotonically increasing identifier, typically derived from the chat
     * session ID.  Once registered, callers may poll {@link #isCancelled(Long)}
     * to detect cancellation requests.
     *
     * @param sessionId the session identifier
     */
    public void startSession(Long sessionId) {
        if (sessionId == null) return;
        cancelFlags.putIfAbsent(sessionId, new AtomicBoolean(false));
    }

    /**
     * Request cancellation of an existing streaming session.  Downstream
     * components should poll {@link #isCancelled(Long)} and abort their
     * work when the flag becomes {@code true}.  If no session is registered
     * for the provided identifier this call is a no-op.
     *
     * @param sessionId the session identifier
     */
    public void cancelSession(Long sessionId) {
        if (sessionId == null) return;
        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            log.info("Cancellation requested for session {}", sessionId);
        }
    }

    /**
     * Determine whether the given streaming session has been cancelled.
     *
     * @param sessionId the session identifier
     * @return {@code true} when cancellation has been requested; otherwise
     * {@code false}
     */
    public boolean isCancelled(Long sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * Remove tracking of a session after completion.  This method should be
     * called once a streaming operation has finished to avoid resource leaks.
     *
     * @param sessionId the session identifier to remove
     */
    public void endSession(Long sessionId) {
        if (sessionId == null) return;
        cancelFlags.remove(sessionId);
    }
}