package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;




/**
 * Admin listing endpoint. For now it is unsecured and should be protected
 * at the gateway/reverse proxy level if exposed publicly.
 */
@RestController
@RequestMapping("/api/admin")
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