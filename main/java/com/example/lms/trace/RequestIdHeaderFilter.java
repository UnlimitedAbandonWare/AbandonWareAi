// src/main/java/com/example/lms/trace/RequestIdHeaderFilter.java
package com.example.lms.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures each response carries correlation headers to make client/server
 * logs joinable. Complements {@code com.example.lms.web.TraceFilter}.
 *
 * - X-Request-Id: mirrors the MDC "trace" value generated upstream
 * - X-Session-Id: mirrors MDC "sid" if present
 */
@Component("exRequestIdHeaderFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestIdHeaderFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(RequestIdHeaderFilter.class);
    private static final String HDR_REQUEST_ID = "X-Request-Id";
    private static final String HDR_SESSION_ID = "X-Session-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String previousRequestId = MDC.get("x-request-id");
        String trace = firstNonBlank(previousRequestId, MDC.get("trace"));
        if (trace == null && request instanceof HttpServletRequest req) {
            trace = firstNonBlank(req.getHeader(HDR_REQUEST_ID));
        }
        if (trace == null) {
            trace = UUID.randomUUID().toString();
        }
        if (request instanceof HttpServletRequest) {
            request.setAttribute(HDR_REQUEST_ID, trace);
        }
        if (previousRequestId == null || previousRequestId.isBlank()) {
            MDC.put("x-request-id", trace);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            HttpServletResponse res = (HttpServletResponse) response;
            String sid = firstNonBlank(MDC.get("sessionId"), MDC.get("sid"));
            res.setHeader(HDR_REQUEST_ID, trace);
            if (sid != null && !sid.isBlank()) {
                res.setHeader(HDR_SESSION_ID, sid);
            }
            if (previousRequestId == null || previousRequestId.isBlank()) {
                MDC.remove("x-request-id");
            } else {
                MDC.put("x-request-id", previousRequestId);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null) {
                String trimmed = v.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return null;
    }
}
