package com.abandonware.ai.agent.room;

import java.time.Instant;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.room.Message
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.room.Message
role: config
*/
public class Message {
    private String id;
    private String roomId;
    private String authorIdentity; // user:<id> or guest:<session>
    private String content;
    private Instant createdAt;

    public Message() {}

    public Message(String id, String roomId, String authorIdentity, String content, Instant createdAt) {
        this.id = id;
        this.roomId = roomId;
        this.authorIdentity = authorIdentity;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getAuthorIdentity() { return authorIdentity; }
    public void setAuthorIdentity(String authorIdentity) { this.authorIdentity = authorIdentity; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}