package com.abandonware.ai.agent.room;

import java.time.Instant;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.room.Room
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.room.Room
role: config
*/
public class Room {
    private String id;
    private String title;
    private String ownerIdentity; // user:<id> or guest:<session>
    private String ownerType;     // USER | GUEST
    private Instant createdAt;
    private Instant migratedAt;   // nullable

    public Room() {}

    public Room(String id, String title, String ownerIdentity, String ownerType, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.ownerIdentity = ownerIdentity;
        this.ownerType = ownerType;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getOwnerIdentity() { return ownerIdentity; }
    public void setOwnerIdentity(String ownerIdentity) { this.ownerIdentity = ownerIdentity; }

    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getMigratedAt() { return migratedAt; }
    public void setMigratedAt(Instant migratedAt) { this.migratedAt = migratedAt; }
}