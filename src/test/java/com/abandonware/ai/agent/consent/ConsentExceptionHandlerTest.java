package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.tool.ToolScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsentExceptionHandlerTest {
    @Test
    void handlerPassesHeaderSessionAndRoomToRendererWhenBridgeIsEmpty() {
        CapturingRenderer renderer = new CapturingRenderer();
        ConsentExceptionHandler handler = new ConsentExceptionHandler(renderer, new ContextBridge());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "session-header");
        request.addHeader("X-Channel-Room-Id", "room-header");

        handler.handle(new ConsentRequiredException(List.of(ToolScope.WEB_GET)), request);

        assertEquals("session-header", renderer.sessionId);
        assertEquals("room-header", renderer.roomId);
    }

    @Test
    void handlerTrimsHeaderSessionAndRoomBeforeRenderingCard() {
        CapturingRenderer renderer = new CapturingRenderer();
        ConsentExceptionHandler handler = new ConsentExceptionHandler(renderer, new ContextBridge());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", " session-header ");
        request.addHeader("X-Channel-Room-Id", " room-header ");

        handler.handle(new ConsentRequiredException(List.of(ToolScope.WEB_GET)), request);

        assertEquals("session-header", renderer.sessionId);
        assertEquals("room-header", renderer.roomId);
    }

    @Test
    void handlerFallsBackToBridgeCurrentWhenHeadersAreMissing() {
        CapturingRenderer renderer = new CapturingRenderer();
        ContextBridge bridge = new ContextBridge();
        bridge.setCurrent(new ChannelRef("room-current", "session-current", null));
        ConsentExceptionHandler handler = new ConsentExceptionHandler(renderer, bridge);

        handler.handle(new ConsentRequiredException(List.of(ToolScope.WEB_GET)), new MockHttpServletRequest());

        assertEquals("session-current", renderer.sessionId);
        assertEquals("room-current", renderer.roomId);
    }

    private static final class CapturingRenderer extends ConsentCardRenderer {
        private String sessionId;
        private String roomId;

        @Override
        public String renderBasic(String sessionId, String roomId, String[] scopes, long ttlSeconds) {
            this.sessionId = sessionId;
            this.roomId = roomId;
            return "{}";
        }
    }
}
