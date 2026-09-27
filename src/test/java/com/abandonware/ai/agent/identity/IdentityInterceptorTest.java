package com.abandonware.ai.agent.identity;

import com.abandonware.ai.agent.context.ContextBridge;
import com.example.lms.search.TraceStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityInterceptorTest {
    @BeforeEach
    @AfterEach
    void clearContext() {
        new ContextBridge().clearCurrent();
        TraceStore.clear();
    }

    @Test
    void preHandlePreservesUuidGidCookie() throws Exception {
        ContextBridge bridge = new ContextBridge();
        IdentityInterceptor interceptor = new IdentityInterceptor(bridge);
        String gid = "11111111-1111-4111-8111-111111111111";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("gid", gid));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertEquals(gid, bridge.current().sessionId());
        assertEquals(gid, cookieValue(response.getHeader("Set-Cookie")));
    }

    @Test
    void preHandleRegeneratesNonUuidGidCookieBeforeReflectingResponseHeader() throws Exception {
        ContextBridge bridge = new ContextBridge();
        IdentityInterceptor interceptor = new IdentityInterceptor(bridge);
        String unsafeGid = "ownerToken-secret";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("gid", unsafeGid));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        String issued = cookieValue(response.getHeader("Set-Cookie"));
        assertNotEquals(unsafeGid, issued);
        assertEquals(issued, bridge.current().sessionId());
        assertDoesNotThrow(() -> UUID.fromString(issued));
        assertFalse(response.getHeader("Set-Cookie").contains(unsafeGid));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.identity.suppressed"));
        assertEquals("gid.parse", TraceStore.get("agent.identity.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("agent.identity.suppressed.errorClass"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(unsafeGid));
    }

    @Test
    void preHandleDoesNotTrustRawForwardedHttpsForCookieSecurity() throws Exception {
        ContextBridge bridge = new ContextBridge();
        IdentityInterceptor interceptor = new IdentityInterceptor(bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        var setCookies = response.getHeaders("Set-Cookie");
        assertEquals(1, setCookies.size());
        String header = setCookies.iterator().next();
        assertFalse(header.contains("; Secure"));
        assertTrue(header.contains("; HttpOnly"));
        assertTrue(header.contains("; SameSite=Lax"));
    }

    @Test
    void preHandleMarksGidCookieSecureFromContainerSecurityState() throws Exception {
        ContextBridge bridge = new ContextBridge();
        IdentityInterceptor interceptor = new IdentityInterceptor(bridge);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertTrue(response.getHeader("Set-Cookie").contains("; Secure"));
    }

    private static String cookieValue(String setCookie) {
        String prefix = "gid=";
        return setCookie.substring(prefix.length(), setCookie.indexOf(';'));
    }
}
