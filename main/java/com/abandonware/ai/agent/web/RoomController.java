package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.identity.IdentityUtils;
import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;




/**
 * Public endpoints for room/message operations bound to the current identity.
 */
@RestController
@RequestMapping("/api")
public class RoomController {

    private final RoomService rooms;
    private final ContextBridge bridge;

    public RoomController(RoomService rooms, ContextBridge bridge) {
        this.rooms = rooms;
        this.bridge = bridge;
    }

    /** Create a room owned by current identity. Body: { "title": "/* ... *&#47;" } */
    @PostMapping("/rooms")
    public ResponseEntity<?> createRoom(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String sessionId = bridge.sessionId(); // from gid cookie or header via interceptors
        String identity = IdentityUtils.identityOf(userId, sessionId);
        if (identity == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "identity_unavailable"));
        }
        String title = body != null && body.get("title") != null ? String.valueOf(body.get("title")) : "Untitled";
        Room created = rooms.create(identity, title);
        return ResponseEntity.ok(RoomView.from(created));
    }

    /** List rooms of current identity. */
    @GetMapping("/rooms")
    public ResponseEntity<?> myRooms(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        if (identity == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "identity_unavailable"));
        }
        return ResponseEntity.ok(rooms.listOf(identity).stream()
                .map(RoomView::from)
                .toList());
    }

    public record RoomView(
            String id,
            String title,
            String ownerType,
            Instant createdAt,
            Instant migratedAt,
            String ownerIdentityHash,
            int ownerIdentityLength) {

        static RoomView from(Room room) {
            String ownerIdentity = room == null ? null : room.getOwnerIdentity();
            return new RoomView(
                    room == null ? null : room.getId(),
                    room == null ? null : room.getTitle(),
                    room == null ? null : room.getOwnerType(),
                    room == null ? null : room.getCreatedAt(),
                    room == null ? null : room.getMigratedAt(),
                    ownerIdentity == null ? "" : com.example.lms.trace.SafeRedactor.hashValue(ownerIdentity),
                    ownerIdentity == null ? 0 : ownerIdentity.length());
        }
    }
}
