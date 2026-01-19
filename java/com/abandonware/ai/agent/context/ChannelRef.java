package com.abandonware.ai.agent.context;


/**
 * Represents a cross-channel reference, linking a Kakao room, session
 * identifier and an n8n execution identifier together.  This mapping allows
 * the agent to translate between different identifiers when passing
 * information between channels.  Additional metadata can be added as
 * required.
 */
public final class ChannelRef {
    private final String roomId;
    private final String sessionId;
    private final String executionId;

    public ChannelRef(String roomId, String sessionId, String executionId) {
        this.roomId = roomId;
        this.sessionId = sessionId;
        this.executionId = executionId;
    }

    public String roomId() {
        return roomId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String executionId() {
        return executionId;
    }
}