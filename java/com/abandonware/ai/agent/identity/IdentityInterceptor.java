package com.abandonware.ai.agent.identity;

import com.abandonware.ai.agent.context.ChannelRef;
import com.abandonware.ai.agent.context.ContextBridge;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.UUID;




/**
 * Ensures every visitor has a stable session identity (guest) even without sign-in.
 * If the request already carries a session via header (X-Session-Id) and another
 * interceptor sets the ContextBridge, this interceptor stays passive.
 *
 * Cookie: gid (HttpOnly, SameSite=Lax) - random UUID string.
 */
public class IdentityInterceptor implements HandlerInterceptor {

    private final ContextBridge bridge;

    public IdentityInterceptor(ContextBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // If another interceptor already established the channel, do nothing.
        if (bridge.current() != null && bridge.current().sessionId() != null) {
            return true;
        }

        // Try to read gid cookie; if missing, create one.
        String gid = readCookie(request, "gid");
        if (gid == null || gid.isBlank()) {
            gid = UUID.randomUUID().toString();
        }
        // 항상 슬라이딩 TTL로 재발급: 같은 gid 값으로 Max-Age 연장
        Cookie cookie = new Cookie("gid", gid);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 180); // 180 days
        response.addHeader("Set-Cookie",
                "gid=" + gid + "; Max-Age=" + (60*60*24*180) + "; Path=/; HttpOnly; SameSite=Lax");
        response.addCookie(cookie);

        // Establish a minimal channel with the session id; room/execution remains null.
        bridge.setCurrent(new ChannelRef(null, gid, null));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // Do not clear here - GlobalExceptionHandler / ConsentInterceptor clears after request.
        // Keeping no-op to avoid interfering with existing lifecycle.
    }

    private static String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}