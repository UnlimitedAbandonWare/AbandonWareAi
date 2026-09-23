package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityControllerTest {

    @Test
    void claimTrimsSessionAndUserBeforeLinkingGuestRooms() {
        RoomService rooms = new RoomService();
        rooms.create("guest:sid-1", "Draft");
        ContextBridge bridge = new ContextBridge();
        bridge.setCurrent(new ChannelRef(null, " sid-1 ", null));
        IdentityController controller = new IdentityController(rooms, bridge);

        ResponseEntity<Map<String, Object>> response = controller.claim(" alice ", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("migratedCount", 1);
        assertThat(rooms.listOf("user:alice")).hasSize(1);
        assertThat(rooms.listOf("user: alice ")).isEmpty();
    }
}
