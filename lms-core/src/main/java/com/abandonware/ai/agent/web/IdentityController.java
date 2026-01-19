package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.web.IdentityController
 * Role: controller
 * Key Endpoints: POST /api/identity/claim, ANY /api/identity/api/identity
 * Dependencies: com.abandonware.ai.agent.context.ContextBridge, com.abandonware.ai.agent.room.RoomService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.web.IdentityController
role: controller
api:
  - POST /api/identity/claim
  - ANY /api/identity/api/identity
*/
public class IdentityController {

    private final RoomService rooms;
    private final ContextBridge bridge;

    public IdentityController(RoomService rooms, ContextBridge bridge) {
        this.rooms = rooms;
        this.bridge = bridge;
    }

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestHeader(value = "X-User-Id", required = false) String headerUser,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        String userId = headerUser != null ? headerUser :
                (body != null && body.get("userId") != null ? String.valueOf(body.get("userId")) : null);
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required (header X-User-Id or body.userId)"));
        }
        String sessionId = bridge.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is not established"));
        }
        Map<String, Object> result = rooms.linkGuestToUser(sessionId, userId);
        return ResponseEntity.ok(result);
    }
}