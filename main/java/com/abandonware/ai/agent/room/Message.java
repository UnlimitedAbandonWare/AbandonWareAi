package com.abandonware.ai.agent.room;

import java.time.Instant;



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