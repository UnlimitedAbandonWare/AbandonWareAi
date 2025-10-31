package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.identity.IdentityUtils;
import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
    public ResponseEntity<Room> createRoom(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String sessionId = bridge.sessionId(); // from gid cookie or header via interceptors
        String identity = IdentityUtils.identityOf(userId, sessionId);
        String title = body != null && body.get("title") != null ? String.valueOf(body.get("title")) : "Untitled";
        Room created = rooms.create(identity, title);
        return ResponseEntity.ok(created);
    }

    /** List rooms of current identity. */
    @GetMapping("/rooms")
    public ResponseEntity<List<Room>> myRooms(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        return ResponseEntity.ok(rooms.listOf(identity));
    }
}