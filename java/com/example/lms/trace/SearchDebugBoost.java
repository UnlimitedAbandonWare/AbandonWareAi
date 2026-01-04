// src/main/java/com/example/lms/trace/SearchDebugBoost.java
package com.example.lms.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global "debug boost" window for search tracing.
 *
 * Goal:
 * - When the system enters a failure mode (e.g., NightmareBreaker trips OPEN),
 *   automatically force dbgSearch=ON for N minutes to capture console traces.
 * - This follows the UAW "Anti-Fragile / Unmasking" intent: surface hidden
 *   failure context immediately instead of silently swallowing it.
 *
 * Notes:
 * - The boost is global, but it is only applied when configured to be enabled.
 * - Filtering is supported so that only specific OPEN kinds/reasons trigger the boost
 *   (e.g., TIMEOUT-only).
 */
@Component
public class SearchDebugBoost {

    private static final Logger log = LoggerFactory.getLogger(SearchDebugBoost.class);

    // Keep consistent with TraceFilter/SearchTraceConsoleLogger.
    private static final String DBG_SEARCH_MDC = "dbgSearch";

    private final boolean enabled;
    private final long boostDurationMs;
    private final List<String> keyPrefixes;

    // Allow-list filters (optional). Keep it simple: CSV of enum names or substrings.
    private final Set<String> kindAllow;
    private final List<String> reasonAllow;
    private final List<String> reasonDeny;

    private final AtomicLong boostUntilMs = new AtomicLong(0L);
    private final AtomicLong lastTriggeredAtMs = new AtomicLong(0L);
    private final AtomicReference<String> lastReason = new AtomicReference<>("");

    public SearchDebugBoost(
            @Value("${lms.debug.search.boost.enabled:false}") boolean enabled,
            @Value("${lms.debug.search.boost.minutes:2}") long minutes,
            @Value("${lms.debug.search.boost.keys:query-transformer:,websearch}") String keysCsv,
            // Example: TIMEOUT (default), or TIMEOUT,RATE_LIMIT
            @Value("${lms.debug.search.boost.kind-allow:TIMEOUT}") String kindAllowCsv,
            // Example: timeout,slow
            @Value("${lms.debug.search.boost.reason-allow:}") String reasonAllowCsv,
            // Example: interrupted,cancel
            @Value("${lms.debug.search.boost.reason-deny:}") String reasonDenyCsv
    ) {
        this.enabled = enabled;

        long m = Math.max(0L, minutes);
        this.boostDurationMs = m * 60_000L;

        this.keyPrefixes = parseCsv(keysCsv);
        this.kindAllow = parseCsvUpperSet(kindAllowCsv);
        this.reasonAllow = parseCsvLower(reasonAllowCsv);
        this.reasonDeny = parseCsvLower(reasonDenyCsv);
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<String> parseCsvUpperSet(String csv) {
        if (csv == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String p : csv.split(",")) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            out.add(t.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> parseCsvLower(String csv) {
        if (csv == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public boolean isActive() {
        if (!enabled) return false;
        return System.currentTimeMillis() < boostUntilMs.get();
    }

    public long remainingMs() {
        if (!enabled) return 0L;
        long rem = boostUntilMs.get() - System.currentTimeMillis();
        return Math.max(0L, rem);
    }

    public Instant lastTriggeredAt() {
        long v = lastTriggeredAtMs.get();
        return v > 0 ? Instant.ofEpochMilli(v) : null;
    }

    public String reason() {
        String r = lastReason.get();
        return (r == null) ? "" : r;
    }

    /**
     * Trigger a debug boost when NightmareBreaker opens.
     *
     * @return true if boost was applied
     */
    public boolean maybeBoostOnNightmareOpen(
            String key,
            String kind,
            String openReason,
            Duration openFor,
            String contextPreview
    ) {
        if (!enabled) return false;
        if (boostDurationMs <= 0L) return false;

        if (!matchesKey(key)) return false;
        if (!matchesKind(kind)) return false;
        if (!matchesReason(openReason)) return false;

        String reason = buildReason(key, kind, openReason, openFor, contextPreview);
        return boost(reason);
    }

    /**
     * Force dbgSearch=ON for the configured duration.
     */
    public boolean boost(String reason) {
        if (!enabled) return false;
        if (boostDurationMs <= 0L) return false;

        long now = System.currentTimeMillis();
        long until = now + boostDurationMs;
        long prev = boostUntilMs.getAndUpdate(old -> Math.max(old, until));

        lastTriggeredAtMs.set(now);
        if (reason != null && !reason.isBlank()) {
            lastReason.set(reason);
        }

        // Best-effort: enable request flag on the *current* thread as well.
        try {
            MDC.put(DBG_SEARCH_MDC, "1");
        } catch (Throwable ignore) {
            // ignore
        }

        // Avoid log spam: only log if we created/extended the window meaningfully.
        if (until > prev + 1000L) {
            try {
                log.warn("[SearchDebugBoost] dbgSearch BOOST enabled for {}ms (until={}) reason={}",
                        boostDurationMs,
                        Instant.ofEpochMilli(until),
                        SafeRedactor.redact(trunc(oneLine(lastReason.get()), 320))
                );
            } catch (Throwable ignore) {
                // ignore
            }
        }
        return true;
    }

    private boolean matchesKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (keyPrefixes == null || keyPrefixes.isEmpty()) return true;
        for (String p : keyPrefixes) {
            if (p == null || p.isBlank()) continue;
            if (key.startsWith(p)) return true;
        }
        return false;
    }

    private boolean matchesKind(String kind) {
        if (kindAllow == null || kindAllow.isEmpty()) return true;
        if (kind == null || kind.isBlank()) return false;
        String k = kind.trim().toUpperCase(Locale.ROOT);
        return kindAllow.contains(k);
    }

    private boolean matchesReason(String openReason) {
        String r = (openReason == null) ? "" : openReason.trim().toLowerCase(Locale.ROOT);
        if (!reasonDeny.isEmpty()) {
            for (String deny : reasonDeny) {
                if (deny == null || deny.isBlank()) continue;
                if (r.contains(deny)) return false;
            }
        }
        if (!reasonAllow.isEmpty()) {
            for (String allow : reasonAllow) {
                if (allow == null || allow.isBlank()) continue;
                if (r.contains(allow)) return true;
            }
            return false;
        }
        return true;
    }

    private static String buildReason(String key, String kind, String openReason, Duration openFor, String ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("nightmare.open key=").append(oneLine(key));
        if (kind != null && !kind.isBlank()) sb.append(" kind=").append(oneLine(kind));
        if (openReason != null && !openReason.isBlank()) sb.append(" reason=").append(trunc(oneLine(openReason), 140));
        if (openFor != null) sb.append(" openFor=").append(openFor);
        if (ctx != null && !ctx.isBlank()) sb.append(" ctx=").append(trunc(oneLine(ctx), 160));
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
