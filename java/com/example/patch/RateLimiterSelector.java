package com.example.patch;


/** Selector for RateLimiter provider; returns noop when provider missing. */
public class RateLimiterSelector {
    public interface Gate { boolean acquire(); }
    static class Noop implements Gate { public boolean acquire(){ return true; } }
    public static Gate upstash() { return new Noop(); } // placeholder
    public static Gate resilience4j(String name, int limit) {
        try {
            Class.forName("io.github.resilience4j.ratelimiter.RateLimiter");
            // For brevity, return noop; integrate with R4j configuration in project.
            return new Noop();
        } catch (Throwable t) { return new Noop(); }
    }
}