// src/main/java/com/example/lms/trace/RequestIdHeaderFilter.java
package com.example.lms.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;




/**
 * Ensures each response carries correlation headers to make client/server
 * logs joinable.  Complements {@code com.example.lms.web.TraceFilter}.
 *
 * - X-Request-Id: mirrors the MDC "trace" value generated upstream
 * - X-Session-Id: mirrors MDC "sid" if present
 */
@Slf4j
@Component("exRequestIdHeaderFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestIdHeaderFilter implements Filter {
    private static final String HDR_REQUEST_ID = "X-Request-Id";
    private static final String HDR_SESSION_ID = "X-Session-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            HttpServletResponse res = (HttpServletResponse) response;
            String trace = MDC.get("trace");
            String sid   = MDC.get("sid");
            if (trace != null && !trace.isBlank()) {
                res.setHeader(HDR_REQUEST_ID, trace);
            }
            if (sid != null && !sid.isBlank()) {
                res.setHeader(HDR_SESSION_ID, sid);
            }
        }
    }
}