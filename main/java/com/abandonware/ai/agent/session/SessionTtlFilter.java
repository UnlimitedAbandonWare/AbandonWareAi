package com.abandonware.ai.agent.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;




/**
 * Minimal TTL enforcement using HttpSession lastAccessedTime.
 * Drop-in fallback when custom ContextBridge TTL is not wired.
 */
@Component
public class SessionTtlFilter extends OncePerRequestFilter {

    private static final System.Logger LOG = System.getLogger(SessionTtlFilter.class.getName());

    @Value("${session.ttl-minutes:45}")
    private long ttlMinutes;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession s = request.getSession(false);
        if (s != null) {
            long idleMillis = System.currentTimeMillis() - s.getLastAccessedTime();
            long ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
            if (idleMillis > ttlMillis) {
                try {
                    s.invalidate();
                } catch (IllegalStateException ex) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Session TTL invalidation skipped stage=session_invalidate errorType="
                                    + ex.getClass().getSimpleName());
                }
                request.getSession(true); // force new session
            }
        }
        filterChain.doFilter(request, response);
    }
}
