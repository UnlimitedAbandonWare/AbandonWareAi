package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionControllerRedactionTest {

    @Test
    void sessionResponseDoesNotExposeRawSessionOrIdentity() {
        SessionController controller = new SessionController(currentSession("session-secret"), new RoomService());

        ResponseEntity<Map<String, Object>> response = controller.session("gid-secret", "alice@example.test");
        Map<String, Object> body = response.getBody();
        String rendered = String.valueOf(body);

        assertEquals(200, response.getStatusCode().value());
        assertFalse(body.containsKey("sessionId"));
        assertFalse(body.containsKey("identity"));
        assertTrue(body.containsKey("sessionIdHash"));
        assertTrue(body.containsKey("identityHash"));
        assertTrue(rendered.contains("ownerType=USER"));
        assertFalse(rendered.contains("session-secret"));
        assertFalse(rendered.contains("alice@example.test"));
        assertFalse(rendered.contains("user:alice@example.test"));
    }

    @Test
    void sessionEndpointRejectsMissingIdentityInsteadOfListingNullOwnerRooms() {
        SessionController controller = new SessionController(emptySession(), new RoomService());

        ResponseEntity<Map<String, Object>> response = controller.session(null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Map.of("error", "identity_unavailable"), response.getBody());
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
