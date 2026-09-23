package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.Message;
import com.abandonware.ai.agent.room.RoomService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageControllerOwnershipTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void foreignRoomReadsReturnRedactedForbiddenResponse() {
        RoomService rooms = new RoomService();
        Room aliceRoom = rooms.create("user:alice", "Alice room");
        rooms.createMessage(aliceRoom.getId(), "user:alice", "private");

        MessageController controller = new MessageController(rooms, currentSession("s-bob"));
        ResponseEntity<?> response = controller.list("bob", aliceRoom.getId(), 10);

        assertEquals(403, response.getStatusCode().value());
        assertEquals(Map.of("error", "room_access_denied"), response.getBody());
        assertFalse(String.valueOf(response.getBody()).contains(aliceRoom.getId()));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.messageController.suppressed"));
        assertEquals("list.accessDenied", TraceStore.get("agent.messageController.suppressed.stage"));
        assertEquals("SecurityException", TraceStore.get("agent.messageController.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(aliceRoom.getId()));
    }

    @Test
    void foreignRoomWritesReturnRedactedForbiddenResponse() {
        RoomService rooms = new RoomService();
        Room aliceRoom = rooms.create("user:alice", "Alice room");

        MessageController controller = new MessageController(rooms, currentSession("s-bob"));
        ResponseEntity<?> response = controller.post("bob", Map.of(
                "roomId", aliceRoom.getId(),
                "content", "cross-room"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals(Map.of("error", "room_access_denied"), response.getBody());
        assertFalse(String.valueOf(response.getBody()).contains(aliceRoom.getId()));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.messageController.suppressed"));
        assertEquals("post.accessDenied", TraceStore.get("agent.messageController.suppressed.stage"));
        assertEquals("SecurityException", TraceStore.get("agent.messageController.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(aliceRoom.getId()));
    }

    @Test
    void successfulMessageResponsesDoNotExposeRawAuthorIdentity() {
        RoomService rooms = new RoomService();
        Room guestRoom = rooms.create("guest:s-secret", "Guest room");
        MessageController controller = new MessageController(rooms, currentSession("s-secret"));

        ResponseEntity<?> posted = controller.post(null, Map.of(
                "roomId", guestRoom.getId(),
                "content", "hello"));
        Object postBody = posted.getBody();

        assertEquals(200, posted.getStatusCode().value());
        assertFalse(postBody instanceof Message);
        assertFalse(String.valueOf(postBody).contains("guest:s-secret"));
        assertTrue(String.valueOf(postBody).contains("authorIdentityHash"));

        @SuppressWarnings("unchecked")
        ResponseEntity<List<?>> listed = (ResponseEntity<List<?>>) (ResponseEntity<?>) controller.list(null, guestRoom.getId(), 10);
        Object listBody = listed.getBody().get(0);
        assertFalse(listBody instanceof Message);
        assertFalse(String.valueOf(listBody).contains("guest:s-secret"));
        assertTrue(String.valueOf(listBody).contains("authorIdentityHash"));
    }

    @Test
    void postMissingRoomIdReturnsInvalidRoomWithoutStringifyingNull() {
        RoomService rooms = new RoomService();
        MessageController controller = new MessageController(rooms, currentSession("s-secret"));

        ResponseEntity<?> posted = controller.post(null, Map.of("content", "hello"));

        assertEquals(400, posted.getStatusCode().value());
        assertEquals(Map.of("error", "invalid_room"), posted.getBody());
        assertEquals(0, TraceStore.get("agent.messageController.suppressed.roomLength"));
    }

    @Test
    void postMissingContentUsesEmptyStringInsteadOfLiteralNull() {
        RoomService rooms = new RoomService();
        Room guestRoom = rooms.create("guest:s-secret", "Guest room");
        MessageController controller = new MessageController(rooms, currentSession("s-secret"));

        ResponseEntity<?> posted = controller.post(null, Map.of("roomId", " " + guestRoom.getId() + " "));

        assertEquals(200, posted.getStatusCode().value());
        MessageController.MessageView body = (MessageController.MessageView) posted.getBody();
        assertEquals("", body.content());
        assertEquals(guestRoom.getId(), body.roomId());
    }

    @Test
    void messageEndpointsRejectMissingIdentityBeforeRoomLookup() {
        RoomService rooms = new RoomService();
        Room room = rooms.create("guest:session-1", "Guest room");
        MessageController controller = new MessageController(rooms, emptySession());

        ResponseEntity<?> posted = controller.post(null, Map.of(
                "roomId", room.getId(),
                "content", "hello"));
        ResponseEntity<?> listed = controller.list(null, room.getId(), 10);

        assertEquals(400, posted.getStatusCode().value());
        assertEquals(Map.of("error", "identity_unavailable"), posted.getBody());
        assertEquals(400, listed.getStatusCode().value());
        assertEquals(Map.of("error", "identity_unavailable"), listed.getBody());
    }

    private static ContextBridge currentSession(String sessionId) {
        ContextBridge bridge = new ContextBridge();
        bridge.setCurrent(new ChannelRef(null, sessionId, null));
        return bridge;
    }

    private static ContextBridge emptySession() {
        ContextBridge bridge = new ContextBridge();
        bridge.clearCurrent();
        return bridge;
    }
}
