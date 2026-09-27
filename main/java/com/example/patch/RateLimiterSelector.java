package com.example.patch;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Selector for RateLimiter provider; returns noop when provider missing. */
public class RateLimiterSelector {
    private static final Logger log = Logger.getLogger(RateLimiterSelector.class.getName());

    public interface Gate { boolean acquire(); }
    static class Noop implements Gate { public boolean acquire(){ return true; } }
    public static Gate upstash() { return new Noop(); } // placeholder
    public static Gate resilience4j(String name, int limit) {
        logFailSoft("resilience4j.disabled", null);
        return new Noop();
    }

    private static void logFailSoft(String stage, Throwable t) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = t == null ? "unknown" : t.getClass().getSimpleName();
            log.fine("[AWX][patch][rate-limiter] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
