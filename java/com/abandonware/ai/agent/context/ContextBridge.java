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
        bySession.put(ref.sessionId(), ref);
        if (ref.roomId() != null) {
            roomToSession.put(ref.roomId(), ref.sessionId());
        }
        if (ref.executionId() != null) {
            execToSession.put(ref.executionId(), ref.sessionId());
        }
    }

    public ChannelRef getBySession(String sessionId) {
        return bySession.get(sessionId);
    }

    public String sessionFromRoom(String roomId) {
        return roomToSession.get(roomId);
    }

    public String sessionFromExec(String execId) {
        return execToSession.get(execId);
    }


    // --- Request-local current channel tracking (set via ConsentInterceptor) ---
    private static final ThreadLocal<ChannelRef> CURRENT = new ThreadLocal<>();

    /** Sets the current channel for the ongoing request. */
    public void setCurrent(ChannelRef ref) { CURRENT.set(ref); }

    /** Clears the current channel after the request is completed. */
    public void clearCurrent() { CURRENT.remove(); }

    /** Returns the current channel reference if present. */
    public ChannelRef current() { return CURRENT.get(); }

    /** Convenience: current sessionId or null. */
    public String sessionId() { return CURRENT.get() != null ? CURRENT.get().sessionId() : null; }

    /** Convenience: current roomId or null. */
    public String roomId() { return CURRENT.get() != null ? CURRENT.get().roomId() : null; }
}