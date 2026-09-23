package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.room.Room;
import com.abandonware.ai.agent.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminControllerRedactionTest {

    @Test
    void adminRoomListingDoesNotExposeRawOwnerIdentity() {
        RoomService rooms = new RoomService();
        Room room = rooms.create("guest:session-secret", "Sensitive owner");
        AdminController controller = new AdminController(rooms);

        ResponseEntity<List<RoomController.RoomView>> response = controller.all("all");
        List<RoomController.RoomView> body = response.getBody();

        assertNotNull(body);
        assertFalse(body.isEmpty());
        Object first = body.get(0);
        assertFalse(first instanceof Room);
        assertFalse(String.valueOf(body).contains("session-secret"));
        assertFalse(String.valueOf(body).contains("guest:session-secret"));
        assertTrue(String.valueOf(body).contains("ownerIdentityHash"));
        assertTrue(String.valueOf(body).contains(room.getId()));
    }
}
