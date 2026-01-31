package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NoiseRoutingGate
 *
 * <p>
 * A small, request-scoped probabilistic gate used to "shake" deterministic
 * bypass/fail-soft paths.
 *
 * <p>
 * Motivation:
 * <ul>
 *   <li>Some bypass/fallback paths are triggered deterministically (ex: COMPRESSION mode),
 *       which can lead to quality cliffs when the trigger is a false positive.</li>
 *   <li>Introduce controlled exploration: allow a small fraction of requests to
 *       execute the normally-bypassed stage, while keeping fail-soft fallbacks in place.</li>
 *   <li>Keep it debuggable: decisions are deterministic per-request by default (seeded
 *       from MDC trace/sid + gateKey) and written into TraceStore.</li>
 * </ul>
 *
 * <p>
 * Fail-soft: never throws.
 */
public final class NoiseRoutingGate {

    /** Global enable switch. Default is OFF to preserve existing deterministic routing. */
    public static final String PROP_ENABLED = "orch.noiseGate.enabled";

    /** If true (default), rolls are deterministic per request for reproducibility. */
    public static final String PROP_DETERMINISTIC = "orch.noiseGate.deterministic";

    private static final String TRACE_PREFIX = "orch.noiseGate.";

    private NoiseRoutingGate() {
        // util
    }

    public record GateDecision(boolean escape, double escapeP, double roll, long seed) {
    }

    /**
     * Decide whether to "escape" a deterministic block/bypass.
     *
     * @param gateKey  stable key, ex: "qtx.compression" or "orch.bypass.silentFailure"
     * @param escapeP  probability in [0..1] to allow execution even though the caller would bypass
     * @param ctx      optional GuardContext (used only for better seeding)
     */
    public static GateDecision decideEscape(String gateKey, double escapeP, GuardContext ctx) {
        try {
            double p = clamp01(escapeP);
            boolean enabled = Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"));
            if (!enabled || p <= 0.0) {
                return new GateDecision(false, p, 1.0, 0L);
            }

            boolean deterministic = Boolean.parseBoolean(System.getProperty(PROP_DETERMINISTIC, "true"));

            long seed = 0L;
            double roll;
            if (deterministic) {
                String trace = safe(MDC.get("trace"));
                String sid = safe(MDC.get("sid"));
                String q = (ctx != null ? safe(ctx.getUserQuery()) : "");
                seed = stableHash64(trace, sid, gateKey, q);
                roll = new Random(seed).nextDouble();
            } else {
                roll = ThreadLocalRandom.current().nextDouble();
            }

            boolean escape = roll < p;

            // Trace breadcrumbs (fail-soft)
            try {
                String k = sanitizeKey(gateKey);
                TraceStore.put(TRACE_PREFIX + k + ".escapeP", round4(p));
                TraceStore.put(TRACE_PREFIX + k + ".roll", round4(roll));
                TraceStore.put(TRACE_PREFIX + k + ".escape", escape);
                if (deterministic) {
                    TraceStore.put(TRACE_PREFIX + k + ".seed", seed);
                }
                if (escape) {
                    TraceStore.inc("orch.noiseGate.escape.count");
                    TraceStore.putIfAbsent("orch.noiseGate.escape.firstKey", k);
                }
            } catch (Throwable ignore) {
                // ignore
            }

            return new GateDecision(escape, p, roll, seed);
        } catch (Throwable ignore) {
            return new GateDecision(false, clamp01(escapeP), 1.0, 0L);
        }
    }

    /* ----------------------- helpers ----------------------- */

    private static String sanitizeKey(String k) {
        if (k == null || k.isBlank()) {
            return "unknown";
        }
        // Keep trace keys stable + filesystem/JSON safe.
        return k.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (x.length() > 200) {
            x = x.substring(0, 200);
        }
        return x;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Stable 64-bit hash (FNV-1a) across JVM versions.
     */
    private static long stableHash64(String... parts) {
        final long FNV_OFFSET = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;

        long h = FNV_OFFSET;
        if (parts != null) {
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                byte[] b = p.getBytes(StandardCharsets.UTF_8);
                for (byte value : b) {
                    h ^= (value & 0xff);
                    h *= FNV_PRIME;
                }
                // separator
                h ^= 0xff;
                h *= FNV_PRIME;
            }
        }
        return h;
    }
}
