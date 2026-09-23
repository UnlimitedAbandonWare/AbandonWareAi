package com.abandonware.ai.agent.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextBridgeTest {
    @Test
    void registerMapsRoomAndExecutionIdsToSessionId() {
        ContextBridge bridge = new ContextBridge();

        bridge.register(new ChannelRef("room-1", "session-1", "exec-1"));

        assertEquals("session-1", bridge.sessionFromRoom("room-1"));
        assertEquals("session-1", bridge.sessionFromExec("exec-1"));
        assertEquals("room-1", bridge.getBySession("session-1").roomId());
    }

    @Test
    void registerIgnoresRefsWithoutSessionId() {
        ContextBridge bridge = new ContextBridge();

        assertDoesNotThrow(() -> bridge.register(new ChannelRef("room-1", null, "exec-1")));

        assertNull(bridge.sessionFromRoom("room-1"));
        assertNull(bridge.sessionFromExec("exec-1"));
    }

    @Test
    void registerTrimsLookupKeys() {
        ContextBridge bridge = new ContextBridge();

        bridge.register(new ChannelRef(" room-1 ", " session-1 ", " exec-1 "));

        assertEquals("session-1", bridge.sessionFromRoom("room-1"));
        assertEquals("session-1", bridge.sessionFromRoom(" room-1 "));
        assertEquals("session-1", bridge.sessionFromExec("exec-1"));
        assertEquals("room-1", bridge.getBySession(" session-1 ").roomId());
        assertEquals("exec-1", bridge.getBySession("session-1").executionId());
    }

    @Test
    void setCurrentNormalizesAndNullClearsThreadLocal() {
        ContextBridge bridge = new ContextBridge();

        bridge.setCurrent(new ChannelRef(" room-1 ", " session-1 ", " exec-1 "));

        assertEquals("room-1", bridge.roomId());
        assertEquals("session-1", bridge.sessionId());

        bridge.setCurrent(null);

        assertNull(bridge.current());
    }
}
