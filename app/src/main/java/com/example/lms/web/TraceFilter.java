
package com.example.lms.web;

import com.example.lms.trace.TraceContext;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;

/** Injects TraceContext and picks up X-Deadline-Ms header as a time budget. */
@Component
@Order(1)
public class TraceFilter implements Filter {

    @Autowired(required = false)
    private TraceContext trace;

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (trace != null && request instanceof HttpServletRequest req) {
            String ms = req.getHeader("X-Deadline-Ms");
            if (ms != null) {
                try {
                    long v = Long.parseLong(ms.trim());
                    trace.startWithBudget(Duration.ofMillis(Math.max(0, v)));
                } catch (NumberFormatException ignore) {}
            }
        }
        chain.doFilter(request, response);
    }
}