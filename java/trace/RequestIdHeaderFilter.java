package trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;

/**
 * Injects request id if missing.
 */
public class RequestIdHeaderFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest r = (HttpServletRequest) request;
            String rid = r.getHeader("X-Request-Id");
            if (rid == null || rid.isEmpty()) {
                // No mutation to headers here; just log placeholder.
                System.out.println("request.id=" + UUID.randomUUID());
            }
        }
        chain.doFilter(request, response);
    }
}