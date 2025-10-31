package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.identity.IdentityUtils;
import com.abandonware.ai.agent.room.Message;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;




/** Message endpoints. */
@RestController
@RequestMapping("/api")
public class MessageController {

    private final RoomService rooms;
    private final ContextBridge bridge;

    public MessageController(RoomService rooms, ContextBridge bridge) {
        this.rooms = rooms;
        this.bridge = bridge;
    }

    /** Post a message: { roomId, content } */
    @PostMapping("/messages")
    public ResponseEntity<Message> post(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                        @RequestBody Map<String, Object> body) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        String roomId = String.valueOf(body.get("roomId"));
        String content = String.valueOf(body.get("content"));
        Message m = rooms.createMessage(roomId, identity, content);
        return ResponseEntity.ok(m);
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<Message>> list(@PathVariable("roomId") String roomId,
                                              @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return ResponseEntity.ok(rooms.getMessages(roomId, limit));
    }
}