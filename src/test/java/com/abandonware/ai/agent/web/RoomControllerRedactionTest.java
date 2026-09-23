package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomControllerRedactionTest {

    @Test
    void roomResponsesDoNotExposeRawOwnerIdentity() {
        RoomService rooms = new RoomService();
        RoomController controller = new RoomController(rooms, currentSession("session-secret"));

        ResponseEntity<?> created = controller.createRoom(null, Map.of("title", "Guest room"));
        Object createdBody = created.getBody();

        assertNotNull(createdBody);
        assertFalse(createdBody instanceof Room);
        assertFalse(String.valueOf(createdBody).contains("session-secret"));
        assertFalse(String.valueOf(createdBody).contains("guest:session-secret"));
        assertTrue(String.valueOf(createdBody).contains("ownerIdentityHash"));

        @SuppressWarnings("unchecked")
        ResponseEntity<List<?>> listed = (ResponseEntity<List<?>>) (ResponseEntity<?>) controller.myRooms(null);
        Object listedBody = listed.getBody().get(0);
        assertFalse(listedBody instanceof Room);
        assertFalse(String.valueOf(listedBody).contains("session-secret"));
        assertTrue(String.valueOf(listedBody).contains("ownerIdentityHash"));
    }

    @Test
    void roomEndpointsRejectMissingIdentityBeforeUsingSharedNullOwner() {
        RoomService rooms = new RoomService();
        RoomController controller = new RoomController(rooms, emptySession());

        ResponseEntity<?> created = controller.createRoom(null, Map.of("title", "No identity"));
        ResponseEntity<?> listed = controller.myRooms(null);

        assertEquals(400, created.getStatusCode().value());
        assertEquals(Map.of("error", "identity_unavailable"), created.getBody());
        assertEquals(400, listed.getStatusCode().value());
        assertEquals(Map.of("error", "identity_unavailable"), listed.getBody());
        assertTrue(rooms.listAdmin("all").isEmpty());
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
