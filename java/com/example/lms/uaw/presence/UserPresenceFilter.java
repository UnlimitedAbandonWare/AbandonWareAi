package com.example.lms.uaw.presence;

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
 * Tracks "real" user requests in an async-safe way.
 * <p>
 * Spring MVC can switch to async (SSE/stream/DeferredResult) where the request
 * thread returns early. We only decrement the counter when the async request
 * truly completes, using an {@link AsyncListener}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class UserPresenceFilter extends OncePerRequestFilter {

    private final UserPresenceTracker tracker;
    private final UserTrafficClassifier classifier;

    public UserPresenceFilter(UserPresenceTracker tracker, UserTrafficClassifier classifier) {
        this.tracker = tracker;
        this.classifier = classifier;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Prevent double-counting on async dispatches.
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!classifier.isUserTraffic(req)) {
            chain.doFilter(req, res);
            return;
        }

        tracker.onUserRequestStart();
        boolean asyncStarted = false;

        try {
            chain.doFilter(req, res);
            asyncStarted = req.isAsyncStarted();
        } finally {
            if (asyncStarted) {
                req.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        tracker.onUserRequestEnd();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        tracker.onUserRequestEnd();
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        tracker.onUserRequestEnd();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        // no-op
                    }
                });
            } else {
                tracker.onUserRequestEnd();
            }
        }
    }
}
