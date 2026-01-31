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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central coordinator:
 * log-pattern -> metrics + JSONL + cooldown.
 *
 * <p>This is intentionally "weakly coupled":
 * it only observes logs / files and adjusts order through an AOP aspect.
 */
public final class FailurePatternOrchestrator {

    private static final String POLICY_DEFAULT = "default";
    private static final String POLICY_LLM_SHORT = "llm_short";
    private static final String POLICY_LLM_ESCALATED = "llm_escalated";

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

    // === Cooldown meta (for debugging / risk diagnostics) ===
    private final ConcurrentHashMap<String, CooldownMeta> cooldownMetaBySource = new ConcurrentHashMap<>();

    // Adaptive throttle for LLM-adjacent CIRCUIT_OPEN signals.
    private final ConcurrentHashMap<String, CircuitOpenAdaptive> circuitOpenAdaptiveBySource = new ConcurrentHashMap<>();

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

        // 2) cooldown decision (for logging + feedback)
        CooldownDecision cd = cooldownDecision(match.kind(), match.source(), tsEpochMillis);

        // 3) JSONL
        if (props.getJsonl().isWriteEnabled()) {
            jsonlWriter.write(new FailurePatternEvent(
                    tsEpochMillis,
                    match.kind(),
                    canonicalSource(match.source()),
                    match.key(),
                    cd.cooldownMs,
                    cd.cooldownPolicy,
                    logger,
                    level,
                    message
            ));
        }

        // 4) feedback: write cooldown
        if (props.getFeedback().isEnabled()) {
            cooldown.recordAt(canonicalSource(match.source()), tsEpochMillis, cd.cooldownMs);
        }
    }

    public boolean isCoolingDown(String canonicalSource) {
        if (!props.isEnabled()) {
            return false;
        }
        reloadFromJsonlIfStale(false);
        return cooldown.isCoolingDown(canonicalSource(canonicalSource));
    }

    public long remainingMs(String canonicalSource) {
        if (!props.isEnabled()) {
            return 0;
        }
        reloadFromJsonlIfStale(false);
        return cooldown.remainingMs(canonicalSource(canonicalSource));
    }

    /**
     * Request-scoped diagnostics helper: exposes last-known cooldown decision and remainingMs.
     *
     * <p>Intended for TraceStore breadcrumbs / probes (low overhead, best-effort).
     */
    public CooldownView inspectCooldown(String canonicalSource) {
        String src = canonicalSource(canonicalSource);
        reloadFromJsonlIfStale(false);
        long rem = cooldown.remainingMs(src);
        CooldownMeta meta = cooldownMetaBySource.get(src);
        if (meta == null) {
            return new CooldownView(src, rem > 0, rem, 0, null, null, 0, false);
        }
        return new CooldownView(
                src,
                rem > 0,
                rem,
                meta.lastCooldownMs,
                meta.lastCooldownPolicy,
                meta.lastKind == null ? null : meta.lastKind.name(),
                meta.circuitOpenStrikes,
                meta.circuitOpenEscalated
        );
    }

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

                            CooldownDecision cd = cooldownDecision(m.kind(), m.source(), now);

                            if (props.getJsonl().isWriteEnabled()) {
                                jsonlWriter.write(new FailurePatternEvent(
                                        now,
                                        FailurePatternKind.CIRCUIT_OPEN,
                                        canonicalSource(m.source()),
                                        m.key(),
                                        cd.cooldownMs,
                                        cd.cooldownPolicy,
                                        cb.getName(),
                                        "WARN",
                                        "Resilience4j state transition to OPEN"
                                ));
                            }
                            if (props.getFeedback().isEnabled()) {
                                cooldown.recordAt(canonicalSource(m.source()), now, cd.cooldownMs);
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

    private record RecentFailure(long tsEpochMillis, FailurePatternMatch match, String sid) {
    }

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

    private CooldownDecision cooldownDecision(FailurePatternKind kind, String source, long tsEpochMillis) {
        String src = canonicalSource(source);
        if (kind == null) {
            recordMeta(src, null, tsEpochMillis, 0, null, 0, false);
            return new CooldownDecision(0, null, 0, false);
        }

        long cooldownMs = cooldownMsFor(kind, src);
        String policy = POLICY_DEFAULT;
        int strikes = 0;
        boolean escalated = false;

        if (kind == FailurePatternKind.CIRCUIT_OPEN) {
            if ("llm".equals(src) || "disambig".equals(src)) {
                policy = POLICY_LLM_SHORT;
                cooldownMs = Duration.ofSeconds(props.getFeedback().getLlmCircuitOpenCooldownSeconds()).toMillis();

                if (props.getFeedback().isLlmCircuitOpenAdaptiveEnabled()) {
                    CircuitOpenAdaptive st = circuitOpenAdaptiveBySource.computeIfAbsent(src, k -> new CircuitOpenAdaptive());
                    synchronized (st) {
                        st.record(tsEpochMillis,
                                props.getFeedback().getLlmCircuitOpenAdaptiveWindowSeconds(),
                                props.getFeedback().getLlmCircuitOpenAdaptiveStrikeThreshold());
                        strikes = st.strikes;
                        if (st.strikes >= Math.max(2, props.getFeedback().getLlmCircuitOpenAdaptiveStrikeThreshold())) {
                            escalated = true;
                        }
                    }

                    if (escalated) {
                        cooldownMs = Duration.ofSeconds(props.getFeedback().getCircuitOpenCooldownSeconds()).toMillis();
                        policy = POLICY_LLM_ESCALATED;
                    }
                }
            }
        }

        recordMeta(src, kind, tsEpochMillis, cooldownMs, policy, strikes, escalated);
        return new CooldownDecision(cooldownMs, policy, strikes, escalated);
    }

    private void recordMeta(String src,
                            FailurePatternKind kind,
                            long tsEpochMillis,
                            long cooldownMs,
                            String policy,
                            int circuitOpenStrikes,
                            boolean circuitOpenEscalated) {
        if (src == null || src.isBlank()) {
            return;
        }
        CooldownMeta meta = cooldownMetaBySource.computeIfAbsent(src, k -> new CooldownMeta());
        meta.lastEventTsEpochMs = tsEpochMillis;
        meta.lastKind = kind;
        meta.lastCooldownMs = cooldownMs;
        meta.lastCooldownPolicy = policy;
        meta.circuitOpenStrikes = circuitOpenStrikes;
        meta.circuitOpenEscalated = circuitOpenEscalated;
    }

    private long cooldownMsFor(FailurePatternKind kind, String source) {
        if (kind == null) {
            return 0;
        }
        return switch (kind) {
            case NAVER_TRACE_TIMEOUT -> Duration.ofSeconds(props.getFeedback().getNaverTraceTimeoutCooldownSeconds()).toMillis();
            case DISAMBIG_FALLBACK -> Duration.ofSeconds(props.getFeedback().getDisambigFallbackCooldownSeconds()).toMillis();
            case CIRCUIT_OPEN -> {
                String src = canonicalSource(source);
                long defaultMs = Duration.ofSeconds(props.getFeedback().getCircuitOpenCooldownSeconds()).toMillis();
                long llmMs = Duration.ofSeconds(props.getFeedback().getLlmCircuitOpenCooldownSeconds()).toMillis();
                if ("llm".equals(src) || "disambig".equals(src)) {
                    yield llmMs;
                }
                yield defaultMs;
            }
        };
    }

    private void reloadFromJsonlIfStale(boolean force) {
        if (!props.getJsonl().isReadEnabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (!force && (now - lastReloadMs) < props.getJsonl().getReloadMinIntervalMs()) {
                return;
            }

            Path p = Path.of(props.getJsonl().getPath());
            if (!Files.exists(p)) {
                lastReloadMs = now;
                return;
            }

            // Only reload if file changed.
            long lm = Files.getLastModifiedTime(p).toMillis();
            if (!force && lm <= lastReloadMs) {
                return;
            }

            // Read tail (bounded)
            List<String> lines = new ArrayList<>();
            int maxLines = Math.max(10, props.getJsonl().getReloadMaxLines());
            try (BufferedReader r = Files.newBufferedReader(p)) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() > maxLines) {
                        lines.remove(0);
                    }
                }
            }

            // Rebuild cooldown from tail
            cooldown.clear();
            for (String l : lines) {
                FailurePatternEvent evt = parseJsonl(l);
                if (evt == null || evt.kind() == null) {
                    continue;
                }
                String src = canonicalSource(evt.source());
                long cdMs = evt.cooldownMs() > 0 ? evt.cooldownMs() : cooldownMsFor(evt.kind(), src);
                String policy = evt.cooldownPolicy();
                if (policy == null || policy.isBlank()) {
                    policy = derivePolicy(evt.kind(), src, cdMs);
                }

                recordMeta(src, evt.kind(), evt.tsEpochMillis(), cdMs, policy, 0, POLICY_LLM_ESCALATED.equals(policy));

                if (props.getFeedback().isEnabled()) {
                    cooldown.recordAt(src, evt.tsEpochMillis(), cdMs);
                }
            }

            lastReloadMs = now;
        } catch (Exception ignored) {
            // fail-soft
        }
    }

    private String derivePolicy(FailurePatternKind kind, String src, long cdMs) {
        if (kind == null) {
            return null;
        }
        if (kind == FailurePatternKind.CIRCUIT_OPEN) {
            long llmBase = Duration.ofSeconds(props.getFeedback().getLlmCircuitOpenCooldownSeconds()).toMillis();
            if (("llm".equals(src) || "disambig".equals(src)) && cdMs <= llmBase) {
                return POLICY_LLM_SHORT;
            }
            if (("llm".equals(src) || "disambig".equals(src)) && cdMs > llmBase) {
                return POLICY_LLM_ESCALATED;
            }
            return POLICY_DEFAULT;
        }
        return POLICY_DEFAULT;
    }

    private FailurePatternEvent parseJsonl(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode n = om.readTree(line);
            long ts = n.path("tsEpochMillis").asLong(0);
            FailurePatternKind kind = parseKind(n.path("kind").asText(null));
            String source = n.path("source").asText(null);
            String key = n.path("key").asText(null);
            long cooldownMs = n.path("cooldownMs").asLong(0);
            String cooldownPolicy = n.path("cooldownPolicy").asText(null);
            String logger = n.path("logger").asText(null);
            String level = n.path("level").asText(null);
            String msg = n.path("message").asText(null);
            return new FailurePatternEvent(ts, kind, source, key, cooldownMs, cooldownPolicy, logger, level, msg);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static FailurePatternKind parseKind(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return FailurePatternKind.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String canonicalSource(String source) {
        String s = safeLower(source);
        if (s.contains("disambig")) {
            return "disambig";
        }
        if (s.contains("query-transformer") || s.contains("transformer")) {
            return "qtx";
        }
        if (s.contains("llm") || s.contains("chat") || s.contains("model") || s.contains("completion")) {
            return "llm";
        }
        if (s.contains("naver") || s.contains("brave") || s.contains("web")) {
            return "web";
        }
        return s;
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

        // QueryTransformer: separate cooldown group to avoid cross-stage contagion.
        if (k.contains("query-transformer") || k.contains("query_transformer") || k.contains("querytransformer")
                || (k.contains("query") && k.contains("transformer"))) {
            return "qtx";
        }

        // LLM/Chat related keys
        if (k.contains("llm") || k.contains("chat") || k.contains("draft")
                || k.contains("completion") || k.contains("model")) {
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

    private static final class CircuitOpenAdaptive {
        long lastTsEpochMs;
        int strikes;

        void record(long tsEpochMs, long windowSeconds, int strikeThreshold) {
            long windowMs = Duration.ofSeconds(Math.max(1, windowSeconds)).toMillis();
            int th = Math.max(2, strikeThreshold);

            if (lastTsEpochMs <= 0 || (tsEpochMs - lastTsEpochMs) > windowMs) {
                strikes = 1;
            } else {
                strikes++;
            }
            lastTsEpochMs = tsEpochMs;

            // clamp to avoid unbounded growth
            if (strikes > (th * 4)) {
                strikes = th * 4;
            }
        }
    }

    private static final class CooldownMeta {
        volatile long lastEventTsEpochMs;
        volatile FailurePatternKind lastKind;
        volatile long lastCooldownMs;
        volatile String lastCooldownPolicy;
        volatile int circuitOpenStrikes;
        volatile boolean circuitOpenEscalated;
    }

    private static final class CooldownDecision {
        final long cooldownMs;
        final String cooldownPolicy;
        final int strikes;
        final boolean escalated;

        CooldownDecision(long cooldownMs, String cooldownPolicy, int strikes, boolean escalated) {
            this.cooldownMs = cooldownMs;
            this.cooldownPolicy = cooldownPolicy;
            this.strikes = strikes;
            this.escalated = escalated;
        }
    }

    /**
     * Lightweight view for request-scoped diagnostics.
     */
    public record CooldownView(
            String source,
            boolean coolingDown,
            long remainingMs,
            long lastCooldownMs,
            String lastCooldownPolicy,
            String lastKind,
            int circuitOpenStrikes,
            boolean circuitOpenEscalated
    ) {
    }
}
