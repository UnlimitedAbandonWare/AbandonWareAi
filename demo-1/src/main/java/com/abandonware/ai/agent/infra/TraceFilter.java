package com.abandonware.ai.agent.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceFilter implements Filter {

    public static final String HDR_REQUEST_ID = "X-Request-Id";
    public static final String HDR_SESSION_ID = "X-Session-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String requestId = headerOr(req, HDR_REQUEST_ID, UUID.randomUUID().toString());
        String sessionId = headerOr(req, HDR_SESSION_ID, "sess-" + UUID.randomUUID());

        MDC.put("requestId", requestId);
        MDC.put("sessionId", sessionId);

        try {
            res.setHeader(HDR_REQUEST_ID, requestId);
            res.setHeader(HDR_SESSION_ID, sessionId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("sessionId");
        }
    }

    private static String headerOr(HttpServletRequest req, String name, String fallback) {
        String v = req.getHeader(name);
        return v == null || v.isBlank() ? fallback : v;
        }
}