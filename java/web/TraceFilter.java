package web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import trace.TraceContext;

/**
 * Extracts X-Brave-Mode / X-RuleBreak-Token to TraceContext.
 */
public class TraceFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest r = (HttpServletRequest) request;
            TraceContext.setBrave("on".equalsIgnoreCase(r.getHeader("X-Brave-Mode")));
            TraceContext.setRuleBreakToken(r.getHeader("X-RuleBreak-Token"));
        }
        chain.doFilter(request, response);
    }
}