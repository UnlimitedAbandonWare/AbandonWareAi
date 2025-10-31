package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.slf4j.MDC;



/**
 * Intercepts incoming requests to extract session/room identifiers and
 * expose them via {@link ContextBridge}. Also constructs a lightweight
 * {@link ConsentToken} bound to the session for downstream use.
 */
public class ConsentInterceptor implements HandlerInterceptor {

    private final ContextBridge contextBridge;

    public ConsentInterceptor(ContextBridge contextBridge) {
        this.contextBridge = contextBridge;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String sessionId = header(request, "X-Session-Id");
        String roomId = header(request, "X-Kakao-Room-Id");
        if (sessionId != null || roomId != null) {
            // Track the current channel
            contextBridge.setCurrent(new ChannelRef(sessionId, roomId, null));
            // Also attach a ConsentToken for downstream factories (as a request attribute)
            request.setAttribute("AGENT_CONSENT_TOKEN", new ConsentToken(sessionId));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) throws Exception {
        // Clear any MDC variables that may have been set during request handling
        MDC.clear();
        // Remove the current channel context from the bridge
        contextBridge.clearCurrent();
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v;
    }
}