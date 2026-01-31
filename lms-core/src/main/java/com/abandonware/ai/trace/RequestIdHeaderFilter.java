package com.abandonware.ai.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component("abRequestIdHeaderFilter")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.trace.RequestIdHeaderFilter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.trace.RequestIdHeaderFilter
role: config
*/
public class RequestIdHeaderFilter implements Filter {
    public static final String REQ_ID = "X-Request-Id";
    public static final String SES_ID = "X-Session-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String rid = req.getHeader(REQ_ID);
        if (rid == null || rid.isEmpty()) rid = UUID.randomUUID().toString();
        request.setAttribute(REQ_ID, rid);
        String sid = req.getHeader(SES_ID);
        if (sid == null || sid.isEmpty()) sid = "anon";
        request.setAttribute(SES_ID, sid);
        chain.doFilter(request, response);
    }
}