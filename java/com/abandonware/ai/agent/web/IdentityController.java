package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;




/**
 * Identity ops: claim guest session data into a user account.
 * Usage: POST /api/identity/claim with header X-User-Id: <userId>
 * or body { "userId": "/* ... *&#47;" }.
 */
@RestController
@RequestMapping("/api/identity")
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