package com.abandonwareai.nova.autolearn;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Async-safe in-flight request tracker.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ActiveRequestsFilter extends OncePerRequestFilter {

    private final ActiveRequestsCounter counter;

    public ActiveRequestsFilter(ActiveRequestsCounter counter) {
        this.counter = counter;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        counter.inc();
        boolean asyncStarted = false;
        try {
            chain.doFilter(req, res);
            asyncStarted = req.isAsyncStarted();
        } finally {
            if (asyncStarted) {
                req.getAsyncContext().addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { counter.dec(); }
                    @Override public void onTimeout(AsyncEvent event) { counter.dec(); }
                    @Override public void onError(AsyncEvent event) { counter.dec(); }
                    @Override public void onStartAsync(AsyncEvent event) { /* no-op */ }
                });
            } else {
                counter.dec();
            }
        }
    }
}
