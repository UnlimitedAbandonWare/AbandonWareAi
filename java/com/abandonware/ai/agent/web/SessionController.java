package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.identity.IdentityUtils;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;




@RestController
@RequestMapping("/api")
public class SessionController {
    private final ContextBridge bridge;
    private final RoomService rooms;

    public SessionController(ContextBridge bridge, RoomService rooms) {
        this.bridge = bridge;
        this.rooms = rooms;
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String,Object>> session(
            @CookieValue(value = "gid", required = false) String gid,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        int roomsCount = rooms.listOf(identity).size();
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "identity", identity,
                "ownerType", IdentityUtils.ownerType(identity),
                "gidCookie", gid != null && !gid.isBlank(),
                "roomsCount", roomsCount
        ));
    }
}