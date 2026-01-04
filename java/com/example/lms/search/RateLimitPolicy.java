package com.example.lms.search;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;




@Component
public class RateLimitPolicy {
    private final AtomicLong remaining = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong resetEpochMs = new AtomicLong(0);
    private final AtomicLong retryAfterMs = new AtomicLong(0);

    public void updateFromHeaders(HttpHeaders h) {
        if (h == null) return;
        long rem = firstLong(h,
                "x-rate-limit-remaining", "x-ratelimit-remaining", "ratelimit-remaining");
        if (rem >= 0) remaining.set(rem);
        long resetSec = firstLong(h,
                "x-rate-limit-reset", "x-ratelimit-reset", "ratelimit-reset");
        if (resetSec > 0) resetEpochMs.set(System.currentTimeMillis() + resetSec * 1000L);
        long retryAfterSec = firstLong(h, "retry-after");
        if (retryAfterSec > 0) retryAfterMs.set(retryAfterSec * 1000L);
    }

    public int allowedExpansions() {
        long rem = remaining.get();
        if (rem == Long.MAX_VALUE) return 9;   // unknown → 넉넉히
        if (rem <= 3)  return 1;
        if (rem <= 10) return 2;
        if (rem <= 20) return 3;
        return 5;
    }

    public long currentDelayMs() {
        long ra = retryAfterMs.get();
        if (ra > 0) return ra;
        if (remaining.get() <= 2 && System.currentTimeMillis() < resetEpochMs.get()) {
            return 400L; // 짧은 보호 지연
        }
        return 0L;
    }

    private static long firstLong(HttpHeaders h, String... names) {
        for (String n : names) {
            String v = h.getFirst(n);
            if (v != null) {
                try { return Long.parseLong(v.trim()); } catch (Exception ignore) {}
            }
        }
        return -1L;
    }
}