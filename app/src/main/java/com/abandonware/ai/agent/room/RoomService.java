package com.abandonware.ai.agent.room;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.room.RoomService
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.room.RoomService
role: service
*/
public class RoomService {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> messagesByRoom = new ConcurrentHashMap<>();

    /** Admin: list rooms (type: all|guest|user) */
    public List<Room> listAdmin(String type) {
        List<Room> all = new ArrayList<>(rooms.values());
        if (type == null || type.equalsIgnoreCase("all")) return all;
        String t = type.equalsIgnoreCase("guest") ? "GUEST" : "USER";
        List<Room> out = new ArrayList<>();
        for (Room r : all) if (t.equals(r.getOwnerType())) out.add(r);
        return out;
    }

    /** Create a room for an identity */
    public Room create(String identity, String title) {
        Room r = new Room();
        r.setId(UUID.randomUUID().toString());
        r.setTitle(title != null ? title : "Untitled");
        r.setOwnerIdentity(identity);
        r.setOwnerType(identity != null && identity.startsWith("user:") ? "USER" : "GUEST");
        r.setCreatedAt(Instant.now());
        rooms.put(r.getId(), r);
        messagesByRoom.putIfAbsent(r.getId(), new CopyOnWriteArrayList<>());
        return r;
    }

    /** List rooms of identity */
    public List<Room> listOf(String identity) {
        List<Room> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            if (Objects.equals(identity, r.getOwnerIdentity())) out.add(r);
        }
        // newest first
        out.sort(Comparator.comparing(Room::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    /** Create a message */
    public Message createMessage(String roomId, String identity, String content) {
        Message m = new Message(UUID.randomUUID().toString(), roomId, identity, content, Instant.now());
        messagesByRoom.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(m);
        return m;
    }

    /** Get messages */
    public List<Message> getMessages(String roomId, int limit) {
        List<Message> ms = new ArrayList<>(messagesByRoom.getOrDefault(roomId, List.of()));
        ms.sort(Comparator.comparing(Message::getCreatedAt));
        if (limit > 0 && ms.size() > limit) {
            return ms.subList(ms.size() - limit, ms.size());
        }
        return ms;
    }

    /** Link guest session data to user identity; returns summary map. */
    public Map<String, Object> linkGuestToUser(String sessionId, String userId) {
        String from = "guest:" + sessionId;
        String to = "user:" + userId;
        int migrated = 0;
        for (Room r : rooms.values()) {
            if (from.equals(r.getOwnerIdentity())) {
                r.setOwnerIdentity(to);
                r.setOwnerType("USER");
                r.setMigratedAt(Instant.now());
                migrated++;
            }
        }
        Map<String, Object> res = new HashMap<>();
        res.put("linked", true);
        res.put("from", from);
        res.put("to", to);
        res.put("migratedCount", migrated);
        return res;
    }

    // Existing minimal API kept for compatibility
    public String ensureRoom(String userId) {
        return userId == null ? "anonymous" : userId;
    }

    public Optional<Room> find(String roomId){
        return Optional.ofNullable(rooms.get(roomId));
    }

    public Map<String,Object> getContext(String roomId){
        return new HashMap<>();
    }
}