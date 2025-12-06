// src/main/java/com/example/lms/web/TraceFilter.java
package com.example.lms.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;




/**
 * Servlet filter that establishes per-request tracing identifiers and
 * propagates session identifiers into the logging MDC.  On each
 * incoming HTTP request a new traceId is generated and placed in the
 * MDC along with a sid extracted from a session attribute, cookie or
 * request header.  This allows downstream log entries to include the
 * identifiers for correlation.  Values are removed from the MDC after
 * the request completes to avoid leakage between threads.
 */
@Slf4j
@Component("exTraceFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter implements Filter {

    private static final String SID_HEADER = "X-Session-Id";
    private static final String SID_COOKIE  = "sid";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String sid = extractSid(req);
        String trace = UUID.randomUUID().toString();
        try {
            if (sid != null) {
                MDC.put("sid", sid);
            }
            MDC.put("trace", trace);
            chain.doFilter(request, response);
        } finally {
            // Clean up to avoid cross-request contamination
            MDC.remove("sid");
            MDC.remove("trace");
        }
    }

    private String extractSid(HttpServletRequest req) {
        // 1. Look for session attribute
        Object attr = req.getSession(false) != null ? req.getSession().getAttribute("sid") : null;
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        // 2. Check header
        String hdr = req.getHeader(SID_HEADER);
        if (hdr != null && !hdr.isBlank()) {
            return hdr.trim();
        }
        // 3. Check cookie
        if (req.getCookies() != null) {
            Optional<Cookie> c = java.util.Arrays.stream(req.getCookies())
                    .filter(cookie -> SID_COOKIE.equalsIgnoreCase(cookie.getName()))
                    .findFirst();
            if (c.isPresent() && c.get().getValue() != null && !c.get().getValue().isBlank()) {
                return c.get().getValue().trim();
            }
        }
        return null;
    }
}