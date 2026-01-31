package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer counters for failure patterns.
 *
 * <p>We intentionally keep tags low-cardinality:
 * kind + source + detail (detail is bounded by our detector).
 */
public final class FailurePatternMetrics {

    private final MeterRegistry registry; // may be null (fail-soft)
    private final String counterName;
    private final boolean enabled;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public FailurePatternMetrics(MeterRegistry registry, NovaFailurePatternProperties props) {
        this.registry = registry;
        this.counterName = props.getMetrics().getCounterName();
        this.enabled = props.getMetrics().isEnabled();
    }

    public void increment(FailurePatternMatch match) {
        if (!enabled || registry == null || match == null) {
            return;
        }
        String kind = safeTag(match.kind() == null ? null : match.kind().name());
        String source = safeTag(match.source());
        String detail = safeTag(match.key());

        String cacheKey = kind + "|" + source + "|" + detail;
        Counter c = counters.computeIfAbsent(cacheKey,
                k -> Counter.builder(counterName)
                        .tag("kind", kind)
                        .tag("source", source)
                        .tag("detail", detail)
                        .register(registry));
        c.increment();
    }

    private static String safeTag(String raw) {
        if (raw == null) {
            return "none";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "none";
        }
        // Micrometer tag best-practice: keep simple and small
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s.toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
