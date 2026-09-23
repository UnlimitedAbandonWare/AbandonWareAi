package com.abandonware.ai.agent.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;



/**
 * In-memory bridge that maps between different channel identifiers.  The
 * bridge maintains forward and reverse lookups between roomIds, sessionIds
 * and n8n executionIds.  This minimal implementation does not handle
 * expiration or persistence; it is intended as a starting point.
 */
public class ContextBridge {
    private final ConcurrentMap<String, ChannelRef> bySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> roomToSession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> execToSession = new ConcurrentHashMap<>();

    public void register(ChannelRef ref) {
        if (ref == null) return;
        String sessionId = normalize(ref.sessionId());
        if (sessionId == null) return;
        String roomId = normalize(ref.roomId());
        String executionId = normalize(ref.executionId());
        ChannelRef normalized = new ChannelRef(roomId, sessionId, executionId);
        bySession.put(sessionId, normalized);
        if (roomId != null) {
            roomToSession.put(roomId, sessionId);
        }
        if (executionId != null) {
            execToSession.put(executionId, sessionId);
        }
    }

    public ChannelRef getBySession(String sessionId) {
        String normalized = normalize(sessionId);
        if (normalized == null) return null;
        return bySession.get(normalized);
    }

    public String sessionFromRoom(String roomId) {
        String normalized = normalize(roomId);
        if (normalized == null) return null;
        return roomToSession.get(normalized);
    }

    public String sessionFromExec(String execId) {
        String normalized = normalize(execId);
        if (normalized == null) return null;
        return execToSession.get(normalized);
    }


    // --- Request-local current channel tracking (set via ConsentInterceptor) ---
    private static final ThreadLocal<ChannelRef> CURRENT = new ThreadLocal<>();

    /** Sets the current channel for the ongoing request. */
    public void setCurrent(ChannelRef ref) {
        if (ref == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new ChannelRef(
                normalize(ref.roomId()),
                normalize(ref.sessionId()),
                normalize(ref.executionId())));
    }

    /** Clears the current channel after the request is completed. */
    public void clearCurrent() { CURRENT.remove(); }

    /** Returns the current channel reference if present. */
    public ChannelRef current() { return CURRENT.get(); }

    /** Convenience: current sessionId or null. */
    public String sessionId() { return CURRENT.get() != null ? CURRENT.get().sessionId() : null; }

    /** Convenience: current roomId or null. */
    public String roomId() { return CURRENT.get() != null ? CURRENT.get().roomId() : null; }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
