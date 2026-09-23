package com.abandonware.ai.agent.room;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomServiceOwnershipTest {

    @Test
    void createMessageRejectsMissingOrForeignRoom() {
        RoomService rooms = new RoomService();
        Room owned = rooms.create("user:alice", "Alice room");

        assertThrows(IllegalArgumentException.class,
                () -> rooms.createMessage("missing-room", "user:alice", "ghost"));
        assertThrows(SecurityException.class,
                () -> rooms.createMessage(owned.getId(), "user:bob", "cross-room"));
    }

    @Test
    void getMessagesForIdentityReturnsOnlyOwnedRoomMessages() {
        RoomService rooms = new RoomService();
        Room aliceRoom = rooms.create("user:alice", "Alice room");
        rooms.createMessage(aliceRoom.getId(), "user:alice", "hello");

        assertEquals(1, rooms.getMessagesForIdentity(aliceRoom.getId(), "user:alice", 10).size());
        assertThrows(SecurityException.class,
                () -> rooms.getMessagesForIdentity(aliceRoom.getId(), "user:bob", 10));
    }

    @Test
    void linkGuestToUserResponseDoesNotExposeRawIdentityValues() {
        RoomService rooms = new RoomService();
        rooms.create("guest:sensitive-session-id", "Guest room");

        Map<String, Object> response = rooms.linkGuestToUser("sensitive-session-id", "alice@example.test");
        String rendered = String.valueOf(response);

        assertEquals(true, response.get("linked"));
        assertEquals(1, response.get("migratedCount"));
        assertTrue(response.containsKey("fromHash"));
        assertTrue(response.containsKey("toHash"));
        assertFalse(response.containsKey("from"));
        assertFalse(response.containsKey("to"));
        assertFalse(rendered.contains("sensitive-session-id"));
        assertFalse(rendered.contains("alice@example.test"));
    }
}
