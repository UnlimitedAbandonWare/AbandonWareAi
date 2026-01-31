package com.abandonware.ai.web;

import com.abandonware.ai.trace.RequestIdHeaderFilter;
import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component("abTraceFilter")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.web.TraceFilter
 * Role: config
 * Dependencies: com.abandonware.ai.trace.RequestIdHeaderFilter
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.web.TraceFilter
role: config
*/
public class TraceFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Object rid = request.getAttribute(RequestIdHeaderFilter.REQ_ID);
        log.debug("trace.request_id={}", rid);
        chain.doFilter(request, response);
    }
}