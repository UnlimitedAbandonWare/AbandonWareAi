package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Central coordinator:
 * log-pattern -> metrics + JSONL + cooldown.
 *
 * <p>This is intentionally "weakly coupled":
 * it only observes logs / files and adjusts order through an AOP aspect.
 */
public final class FailurePatternOrchestrator {

    private final FailurePatternDetector detector;
    private final FailurePatternMetrics metrics;
    private final FailurePatternJsonlWriter jsonlWriter;
    private final FailurePatternCooldownRegistry cooldown;
    private final ObjectMapper om;
    private final NovaFailurePatternProperties props;

    private volatile long lastReloadMs = 0;

// === In-memory recent matches (for request-scoped feedback / learning) ===
private static final int RECENT_MAX_DEFAULT = 2048;
private final ArrayDeque<RecentFailure> recent = new ArrayDeque<>(RECENT_MAX_DEFAULT);
private final Object recentLock = new Object();


    public FailurePatternOrchestrator(FailurePatternDetector detector,
                                     FailurePatternMetrics metrics,
                                     FailurePatternJsonlWriter jsonlWriter,
                                     FailurePatternCooldownRegistry cooldown,
                                     ObjectMapper om,
                                     NovaFailurePatternProperties props) {
        this.detector = detector;
        this.metrics = metrics;
        this.jsonlWriter = jsonlWriter;
        this.cooldown = cooldown;
        this.om = om;
        this.props = props;

        // Seed once (best-effort)
        reloadFromJsonlIfStale(true);
    }

    public void onLogEvent(long tsEpochMillis, String logger, String level, String message) {
        if (!props.isEnabled()) {
            return;
        }
        FailurePatternMatch match = detector.detect(logger, message);
        if (match == null) {
            return;
        }

        // 0) keep short in-memory tail for per-request feedback
        rememberRecent(tsEpochMillis, match);

        // 1) metrics
        metrics.increment(match);

        // 2) JSONL
        if (props.getJsonl().isWriteEnabled()) {
            jsonlWriter.write(new FailurePatternEvent(
                    tsEpochMillis,
                    match.kind(),
                    match.source(),
                    match.key(),
                    logger,
                    level,
                    message
            ));
        }

        // 3) cooldown (weak feedback)
        if (props.getFeedback().isEnabled()) {
            cooldown.recordAt(match.source(), tsEpochMillis, cooldownMsFor(match.kind()));
        }
    }

    public boolean isCoolingDown(String canonicalSource) {
        if (!props.getFeedback().isEnabled()) {
            return false;
        }
        reloadFromJsonlIfStale(false);
        return cooldown.isCoolingDown(canonicalSource);
    }

    public long remainingMs(String canonicalSource) {
        if (!props.getFeedback().isEnabled()) {
            return 0;
        }
        reloadFromJsonlIfStale(false);
        return cooldown.remainingMs(canonicalSource);
    }


/**
 * Recent failure-pattern matches observed in-process since the given timestamp.
 *
 * <p>Intended for lightweight, per-request feedback loops (e.g. CFVM/online tuning).
 * This does <b>not</b> read JSONL; it uses a bounded in-memory tail fed by onLogEvent().
 */
public List<FailurePatternMatch> recentMatchesSince(long sinceEpochMillis, String sid) {
    if (!props.isEnabled()) {
        return List.of();
    }
    synchronized (recentLock) {
        if (recent.isEmpty()) {
            return List.of();
        }
        List<FailurePatternMatch> out = new ArrayList<>();
        for (RecentFailure rf : recent) {
            if (rf == null) {
                continue;
            }
            if (rf.tsEpochMillis < sinceEpochMillis) {
                continue;
            }
            if (sid != null && rf.sid != null && !sid.equals(rf.sid)) {
                continue;
            }
            // If sid is provided but the event has no sid, include it (best-effort).
            if (sid != null && rf.sid == null) {
                // keep
            }
            out.add(rf.match);
        }
        return out;
    }
}

    /**
     * Optional: Resilience4j registry event consumer.
     *
     * <p>Counts OPEN transitions with the same Micrometer counter,
     * and can also feed cooldown when the breaker name maps to known sources.
     */
    public RegistryEventConsumer<CircuitBreaker> resilience4jRegistryEventConsumer() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
                CircuitBreaker cb = entryAddedEvent.getAddedEntry();
                cb.getEventPublisher().onStateTransition(ev -> {
                    try {
                        var to = ev.getStateTransition().getToState();
                        if (to == CircuitBreaker.State.OPEN || to == CircuitBreaker.State.FORCED_OPEN) {
                            long now = System.currentTimeMillis();
                            String key = safeLower(cb.getName());
                            FailurePatternMatch m = new FailurePatternMatch(
                                    FailurePatternKind.CIRCUIT_OPEN,
                                    inferSourceFromKey(key),
                                    key
                            );
                            metrics.increment(m);
                            rememberRecent(now, m);
                            if (props.getJsonl().isWriteEnabled()) {
                                jsonlWriter.write(new FailurePatternEvent(
                                        now,
                                        FailurePatternKind.CIRCUIT_OPEN,
                                        m.source(),
                                        m.key(),
                                        cb.getName(),
                                        "WARN",
                                        "Resilience4j state transition to OPEN"
                                ));
                            }
                            if (props.getFeedback().isEnabled()) {
                                cooldown.recordAt(m.source(), now, cooldownMsFor(FailurePatternKind.CIRCUIT_OPEN));
                            }
                        }
                    } catch (Exception ignored) {
                        // fail-soft
                    }
                });
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
                // no-op
            }
        };
    }


private record RecentFailure(long tsEpochMillis, FailurePatternMatch match, String sid) {}

private void rememberRecent(long tsEpochMillis, FailurePatternMatch match) {
    try {
        if (match == null) {
            return;
        }
        String sid = null;
        try {
            sid = MDC.get("sid");
        } catch (Exception ignored) {
            // fail-soft
        }
        synchronized (recentLock) {
            while (recent.size() >= RECENT_MAX_DEFAULT) {
                recent.removeFirst();
            }
            recent.addLast(new RecentFailure(tsEpochMillis, match, sid));
        }
    } catch (Exception ignored) {
        // fail-soft
    }
}

    private long cooldownMsFor(FailurePatternKind kind) {
        if (kind == null) {
            return 0;
        }
        return switch (kind) {
            case NAVER_TRACE_TIMEOUT -> Duration.ofSeconds(props.getFeedback().getNaverTraceTimeoutCooldownSeconds()).toMillis();
            case CIRCUIT_OPEN -> Duration.ofSeconds(props.getFeedback().getCircuitOpenCooldownSeconds()).toMillis();
            case DISAMBIG_FALLBACK -> Duration.ofSeconds(props.getFeedback().getDisambigFallbackCooldownSeconds()).toMillis();
        };
    }

    private void reloadFromJsonlIfStale(boolean force) {
        try {
            long now = System.currentTimeMillis();
            long interval = props.getJsonl().getReloadIntervalMs();
            if (!force && interval > 0 && (now - lastReloadMs) < interval) {
                return;
            }
            lastReloadMs = now;

            Path p = jsonlWriter.path();
            if (p == null || !Files.exists(p)) {
                return;
            }
            long size = Files.size(p);
            if (size > props.getJsonl().getMaxFileBytes()) {
                return;
            }

            long cutoff = now - Duration.ofSeconds(props.getJsonl().getLookbackSeconds()).toMillis();
            int tail = Math.max(1, props.getJsonl().getReadTailLines());

            ArrayDeque<String> buf = new ArrayDeque<>(tail);
            try (BufferedReader r = Files.newBufferedReader(p)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (buf.size() == tail) {
                        buf.removeFirst();
                    }
                    buf.addLast(line);
                }
            }

            for (String line : buf) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                FailurePatternEvent evt = parseJsonl(line);
                if (evt == null || evt.tsEpochMillis() < cutoff || evt.kind() == null) {
                    continue;
                }
                if (props.getFeedback().isEnabled()) {
                    cooldown.recordAt(evt.source(), evt.tsEpochMillis(), cooldownMsFor(evt.kind()));
                }
            }
        } catch (Exception ignored) {
            // fail-soft
        }
    }

    private FailurePatternEvent parseJsonl(String line) {
        try {
            JsonNode n = om.readTree(line);
            long ts = n.path("tsEpochMillis").asLong(0);
            if (ts == 0) {
                ts = n.path("ts").asLong(0);
            }
            String kindStr = n.path("kind").asText(null);
            FailurePatternKind kind = parseKind(kindStr);
            String source = n.path("source").asText("web");
            String key = n.path("key").asText(null);
            String logger = n.path("logger").asText(null);
            String level = n.path("level").asText(null);
            String msg = n.path("message").asText(null);
            return new FailurePatternEvent(ts, kind, source, key, logger, level, msg);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static FailurePatternKind parseKind(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim();
        if (k.isEmpty()) {
            return null;
        }
        k = k.toUpperCase(Locale.ROOT);
        try {
            return FailurePatternKind.valueOf(k);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String inferSourceFromKey(String keyLower) {
        if (keyLower == null) {
            return "web";
        }
        String k = keyLower;
        if (k.contains("vector") || k.contains("rag")) {
            return "vector";
        }
        if (k.contains("kg") || k.contains("graph")) {
            return "kg";
        }
        if (k.contains("disambig")) {
            return "disambig";
        }

        // LLM/Chat 관련 키 분류 추가
        if (k.contains("llm") || k.contains("chat") || k.contains("draft")
                || k.contains("completion") || k.contains("query-transformer")
                || k.contains("transformer") || k.contains("model")) {
            return "llm";
        }

        return "web";
    }

    private static String safeLower(String s) {
        if (s == null) {
            return "unknown";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "unknown";
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
