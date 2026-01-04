package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.web.AdminController
 * Role: controller
 * Key Endpoints: GET /api/admin/rooms, ANY /api/admin/api/admin
 * Dependencies: com.abandonware.ai.agent.room.Room, com.abandonware.ai.agent.room.RoomService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.web.AdminController
role: controller
api:
  - GET /api/admin/rooms
  - ANY /api/admin/api/admin
*/
public class AdminController {

    private final RoomService rooms;

    public AdminController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<Room>> all(@RequestParam(name = "type", defaultValue = "all") String type) {
        return ResponseEntity.ok(rooms.listAdmin(type));
    }
}