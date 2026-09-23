package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.identity.IdentityUtils;
import com.abandonware.ai.agent.room.Message;
import com.abandonware.ai.agent.room.RoomService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
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
    public ResponseEntity<?> post(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @RequestBody Map<String, Object> body) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        if (identity == null) {
            return ResponseEntity.badRequest().body(error("identity_unavailable"));
        }
        String roomId = trimToNull(bodyValue(body, "roomId"));
        String content = bodyValue(body, "content");
        if (content == null) {
            content = "";
        }
        try {
            Message m = rooms.createMessage(roomId, identity, content);
            return ResponseEntity.ok(MessageView.from(m));
        } catch (IllegalArgumentException e) {
            traceSuppressed("post.invalidRoom", roomId, identity, e);
            return ResponseEntity.badRequest().body(error("invalid_room"));
        } catch (SecurityException e) {
            traceSuppressed("post.accessDenied", roomId, identity, e);
            return ResponseEntity.status(403).body(error("room_access_denied"));
        }
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<?> list(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable("roomId") String roomId,
                                  @RequestParam(name = "limit", defaultValue = "200") int limit) {
        String sessionId = bridge.sessionId();
        String identity = IdentityUtils.identityOf(userId, sessionId);
        if (identity == null) {
            return ResponseEntity.badRequest().body(error("identity_unavailable"));
        }
        try {
            return ResponseEntity.ok(rooms.getMessagesForIdentity(roomId, identity, limit).stream()
                    .map(MessageView::from)
                    .toList());
        } catch (IllegalArgumentException e) {
            traceSuppressed("list.invalidRoom", roomId, identity, e);
            return ResponseEntity.badRequest().body(error("invalid_room"));
        } catch (SecurityException e) {
            traceSuppressed("list.accessDenied", roomId, identity, e);
            return ResponseEntity.status(403).body(error("room_access_denied"));
        }
    }

    private static Map<String, String> error(String code) {
        return Map.of("error", code);
    }

    private static String bodyValue(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void traceSuppressed(String stage, String roomId, String identity, Throwable error) {
        TraceStore.put("agent.messageController.suppressed", true);
        TraceStore.put("agent.messageController.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.messageController.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.messageController.suppressed.roomHash", SafeRedactor.hashValue(roomId));
        TraceStore.put("agent.messageController.suppressed.roomLength", roomId == null ? 0 : roomId.length());
        TraceStore.put("agent.messageController.suppressed.identityHash", SafeRedactor.hashValue(identity));
        TraceStore.put("agent.messageController.suppressed.identityLength", identity == null ? 0 : identity.length());
    }

    public record MessageView(
            String id,
            String roomId,
            String content,
            Instant createdAt,
            String authorIdentityHash,
            int authorIdentityLength) {

        static MessageView from(Message message) {
            String authorIdentity = message == null ? null : message.getAuthorIdentity();
            return new MessageView(
                    message == null ? null : message.getId(),
                    message == null ? null : message.getRoomId(),
                    message == null ? null : message.getContent(),
                    message == null ? null : message.getCreatedAt(),
                    authorIdentity == null ? "" : SafeRedactor.hashValue(authorIdentity),
                    authorIdentity == null ? 0 : authorIdentity.length());
        }
    }
}
