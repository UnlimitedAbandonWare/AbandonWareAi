package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.context.ChannelRef;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ConsentInterceptorTest {
    @Test
    void preHandleMapsHeadersToChannelRefInConstructorOrder() throws Exception {
        ContextBridge bridge = new ContextBridge();
        ConsentInterceptor interceptor = new ConsentInterceptor(bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", "session-1");
        request.addHeader("X-Channel-Room-Id", "room-1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("session-1", bridge.current().sessionId());
        assertEquals("room-1", bridge.current().roomId());
        ConsentToken token = assertInstanceOf(ConsentToken.class, request.getAttribute("AGENT_CONSENT_TOKEN"));
        assertEquals("session-1", token.sessionId());
    }

    @Test
    void preHandlePreservesExistingSessionWhenOnlyRoomHeaderIsProvided() throws Exception {
        ContextBridge bridge = new ContextBridge();
        bridge.setCurrent(new ChannelRef(null, "gid-session", null));
        ConsentInterceptor interceptor = new ConsentInterceptor(bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Channel-Room-Id", "room-1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("gid-session", bridge.current().sessionId());
        assertEquals("room-1", bridge.current().roomId());
        ConsentToken token = assertInstanceOf(ConsentToken.class, request.getAttribute("AGENT_CONSENT_TOKEN"));
        assertEquals("gid-session", token.sessionId());
    }

    @Test
    void preHandleTrimsHeaderValuesBeforeCreatingConsentToken() throws Exception {
        ContextBridge bridge = new ContextBridge();
        ConsentInterceptor interceptor = new ConsentInterceptor(bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", " session-1 ");
        request.addHeader("X-Channel-Room-Id", " room-1 ");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("session-1", bridge.current().sessionId());
        assertEquals("room-1", bridge.current().roomId());
        ConsentToken token = assertInstanceOf(ConsentToken.class, request.getAttribute("AGENT_CONSENT_TOKEN"));
        assertEquals("session-1", token.sessionId());
    }
}
