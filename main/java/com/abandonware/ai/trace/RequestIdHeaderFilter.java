package com.abandonware.ai.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component("abRequestIdHeaderFilter")
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