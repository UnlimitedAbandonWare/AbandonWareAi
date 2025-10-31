// src/main/java/com/example/lms/debug/HttpDumpFilter.java
package com.example.lms.debug;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(name="lms.debug.enabled", havingValue="true", matchIfMissing=false)
public class HttpDumpFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(HttpDumpFilter.class);
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        log.trace("[HTTP][IN] {} {}", r.getMethod(), r.getRequestURI());
        chain.doFilter(req, res);
    }
}