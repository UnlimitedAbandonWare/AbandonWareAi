
package com.example.lms.context;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class ActiveRequestsFilter extends OncePerRequestFilter {
    private final ActiveRequestsCounter counter;
    public ActiveRequestsFilter(ActiveRequestsCounter counter){ this.counter = counter; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        counter.inc();
        try { chain.doFilter(req, res); }
        finally { counter.dec(); }
    }
}
