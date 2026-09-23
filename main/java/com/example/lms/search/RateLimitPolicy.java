package com.example.lms.search;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;




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
        long raMs = parseRetryAfterMs(h);
        // If the header is absent, clear; if present but unparsable, treat as 0.
        if (raMs >= 0) {
            retryAfterMs.set(raMs);
        } else {
            retryAfterMs.set(0L);
        }
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

    /** Last observed Retry-After (ms), if any. */
    public long retryAfterMs() {
        return retryAfterMs.get();
    }


    private static long parseRetryAfterMs(HttpHeaders h) {
        if (h == null) {
            return -1L;
        }
        String raw = firstHeader(h, "Retry-After", "retry-after", "RETRY-AFTER");
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        String v = raw.trim();

        // Retry-After can be either delay-seconds or an HTTP-date.
        // 1) delay-seconds
        try {
            long sec = Long.parseLong(v);
            if (sec <= 0) {
                return 0L;
            }
            return Math.min(sec, 60L) * 1000L;
        } catch (NumberFormatException ex) {
            traceSuppressed("retryAfter.secondsParseFallback", ex);
            // fallthrough to HTTP-date
        }

        // 2) HTTP-date (RFC_1123_DATE_TIME)
        try {
            ZonedDateTime dt = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaMs = dt.toInstant().toEpochMilli() - System.currentTimeMillis();
            if (deltaMs <= 0) {
                return 0L;
            }
            return Math.min(60_000L, deltaMs);
        } catch (DateTimeParseException ex) {
            traceSuppressed("retryAfter.dateParseFallback", ex);
            return 0L;
        }
    }

    private static String firstHeader(HttpHeaders h, String... names) {
        for (String n : names) {
            String v = h.getFirst(n);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static long firstLong(HttpHeaders h, String... names) {
        for (String n : names) {
            String v = h.getFirst(n);
            if (v != null) {
                try { return Long.parseLong(v.trim()); } catch (NumberFormatException ex) { traceSuppressed(headerStage(n), ex); }
            }
        }
        return -1L;
    }

    private static String headerStage(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("remaining")) {
            return "remaining.parseFallback";
        }
        if (n.contains("reset")) {
            return "reset.parseFallback";
        }
        return "unknown.parseFallback";
    }

    private static void traceSuppressed(String stage, RuntimeException ex) {
        String prefix = "rateLimit.header." + stage;
        TraceStore.inc(prefix + ".count");
        TraceStore.put(prefix + ".errorType", errorTypeFor(stage));
    }

    private static String errorTypeFor(String stage) {
        String value = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        return value.contains("date") ? "invalid_date" : "invalid_number";
    }
}
