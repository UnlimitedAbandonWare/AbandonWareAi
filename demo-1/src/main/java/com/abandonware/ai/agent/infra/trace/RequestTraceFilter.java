package com.abandonware.ai.agent.infra.trace;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestTraceFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest req) {
            String rid = req.getHeader("X-Request-Id");
            if (rid == null || rid.isBlank()) rid = UUID.randomUUID().toString();
            MDC.put("requestId", rid);
            String sid = req.getHeader("X-Session-Id");
            if (sid != null) MDC.put("sessionId", sid);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}