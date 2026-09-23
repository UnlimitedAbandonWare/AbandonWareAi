package com.abandonware.ai.web;

import com.abandonware.ai.trace.RequestIdHeaderFilter;
import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component("abTraceFilter")
public class TraceFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Object rid = request.getAttribute(RequestIdHeaderFilter.REQ_ID);
        log.debug("trace.request_id={}", rid);
        chain.doFilter(request, response);
    }
}