package com.example.lms.infra.resilience;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import dev.langchain4j.exception.HttpException;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.springframework.http.HttpHeaders;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * NightmareBreaker:
 * - 오케스트레이션 단계(전처리/보조 LLM/메인 LLM)에서 발생하는 timeout/blank/429 등을
 * key 단위로 집계하고, 짧은 기간 OPEN 상태로 만들어 연쇄 지연을 차단한다.
 *
 * 개선 포인트 (UAW + Errorxs log 기반)
 * - 성공 시 연속 카운터 리셋(비연속 blank 누적 방지)
 * - slow-call / silent-failure 기반 trip 옵션
 * - 공통 실행 래퍼 execute(...) 제공
 * - 예외 분류 classify(Throwable) 중앙집중화
 */
public class NightmareBreaker {

    private static final Logger log = LoggerFactory.getLogger(NightmareBreaker.class);

    /**
     * Request-scoped trace key: Map&lt;breakerKey, openAtEpochMillis&gt;.
     *
     * <p>AuxBlockTracker 가 "breakerOpenAt" 을 best-effort 로 채울 때 활용합니다.</p>
     */
    public static final String TRACE_OPEN_AT_MS_KEY = "nightmare.breaker.openAtMs";

    /**
     * Request-scoped trace key: Map&lt;breakerKey, openUntilEpochMillis&gt; (optional).
     *
     * <p>동시에 여러 breaker 가 OPEN 일 수 있기 때문에, openUntil 은 key별로 기록합니다.</p>
     */
    public static final String TRACE_OPEN_UNTIL_MS_KEY = "nightmare.breaker.openUntilMs";

    /** Request-scoped trace key: Map<breakerKey, FailureKind> (optional). */
    public static final String TRACE_OPEN_KIND_KEY = "nightmare.breaker.openKind";

    /** Request-scoped trace key: Map<breakerKey, lastErrorMessage> (optional). */
    public static final String TRACE_OPEN_ERRMSG_KEY = "nightmare.breaker.openErrMsg";

    /** Request-scoped trace key for ecosystem recirculation breadcrumbs. */
    public static final String TRACE_ECOSYSTEM_RECIRCULATE_KEY = "ecosystem.breaker.recirculate";

    /** Request-scoped trace key for ecosystem low-trust surge breadcrumbs. */
    public static final String TRACE_ECOSYSTEM_AMMONIA_SURGE_KEY = "ecosystem.breaker.ammoniaSurge";

    /**
     * Request-scoped trace key: 마지막으로 관측한 openUntilEpochMillis (optional, quick debugging).
     *
     * <p>TRACE_OPEN_UNTIL_MS_KEY 를 key별 map 으로 저장하면서도, 단일 값으로 빠르게 확인할 수 있게 남깁니다.</p>
     */
    public static final String TRACE_OPEN_UNTIL_MS_LAST_KEY = "nightmare.breaker.openUntilMs.last";

    public enum FailureKind {
        TIMEOUT,
        INTERRUPTED,
        REJECTED,
        RATE_LIMIT,

        /** Configuration/request schema error (e.g. "model is required"). */
        CONFIG,

        HTTP_4XX,
        HTTP_5XX,
        EMPTY_RESPONSE,
        UNKNOWN
    }

    /** Circuit breaker mode: CLOSED → OPEN → HALF_OPEN → CLOSED. */
    public enum BreakerMode {
        CLOSED, OPEN, HALF_OPEN
    }

    public enum SignalType {
        OPENED, OPEN_EXTENDED, HALF_OPEN_ENTERED, ADMISSION_BLOCKED, ADMISSION_BYPASSED, CLOSED
    }

    /** Public transition/observation event; every text field is a fixed label or redacted key. */
    public record StateSignal(
            NightmareBreaker sourceBreaker,
            String diagnosticKey,
            boolean webSearchKey,
            SignalType signalType,
            BreakerMode mode,
            FailureKind lastKind,
            long openSinceMs,
            long openUntilMs) {
    }

    /** Immutable policy captured at admission to prevent completion-time property tearing. */
    private record PolicySnapshot(
            NightmareBreakerProperties.EffectivePolicy effective,
            boolean tripOnInterrupt,
            boolean rateLimitCountsAsFailure,
            boolean timeoutCountsAsFailure,
            Duration rateLimitOpenDuration,
            Duration rateLimitMaxOpenDuration,
            Duration timeoutOpenDuration,
            Duration timeoutMaxOpenDuration,
            Duration configOpenDuration,
            double backoffBase) {
    }

    private final NightmareBreakerProperties props;

    // Optional: when the breaker opens, force dbgSearch console tracing for a short window
    // to unmask silent failures (UAW: Anti-Fragile / Unmasking).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.trace.SearchDebugBoost searchDebugBoost;

    /** Structured observability for NightmareBreaker (DebugEventStore is optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    /** The sole mutable breaker state owner. */
    private final ConcurrentHashMap<String, Gate> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NightmareBreakerProperties.EffectivePolicy> policyCache = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LongSupplier ticker;

    public NightmareBreaker(NightmareBreakerProperties props) {
        this(props, Clock.systemUTC(), System::nanoTime);
    }

    NightmareBreaker(NightmareBreakerProperties props, Clock clock, LongSupplier ticker) {
        this.props = props;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    @Scheduled(fixedDelayString = "${nightmare.breaker.evict-interval-ms:300000}")
    void evictStaleStates() {
        if (!props.isEnabled()) {
            return;
        }
        long now = nowMs();
        long cutoffTick = ticker.getAsLong() - Duration.ofMinutes(5).toNanos();
        int before = states.size();
        states.forEach((key, gate) -> {
            if (gate == null) return;
            for (;;) {
                GateState state = gate.state.get();
                if (state.retired || state.mode != BreakerMode.CLOSED || state.inFlight != 0
                        || state.lastActivityTick >= cutoffTick) return;
                GateState retired = state.retired(now, ticker.getAsLong());
                if (gate.state.compareAndSet(state, retired)) {
                    states.remove(key, gate);
                    return;
                }
            }
        });
        policyCache.keySet().removeIf(k -> !states.containsKey(k));
        int removed = Math.max(0, before - states.size());
        if (removed > 0) {
            log.debug("[NightmareBreaker] evicted stale states removed={} remaining={}", removed, states.size());
        }
    }

    private void emitEvent(DebugEventLevel level,
            String fingerprint,
            String message,
            String where,
            Map<String, Object> data,
            Throwable error) {
        DebugEventStore store = this.debugEventStore;
        if (store == null) {
            return;
        }
        try {
            store.emit(
                    DebugProbeType.NIGHTMARE_BREAKER,
                    (level == null ? DebugEventLevel.INFO : level),
                    fingerprint,
                    message,
                    where,
                    data,
                    error);
        } catch (Throwable ignore) {
            recordDebugEventEmitFailure(ignore);
        }
    }

    private void recordDebugEventEmitFailure(Throwable failure) {
        try {
            TraceStore.inc("nightmare.debugEvent.emit.failed");
            if (failure != null) {
                TraceStore.put("nightmare.debugEvent.emit.failureClass", "nightmare_debug_event_emit_failed");
            }
        } catch (Throwable ignore) {
            log.trace("[NightmareBreaker] DebugEventStore failure breadcrumb failed: {}",
                    ignore.getClass().getSimpleName());
        }
    }

    private boolean isRequestFailSoftBypassEnabled(
            String key,
            long remainingMs,
            long openAtMs,
            long openUntilMs,
            FailureKind lastKind) {
        try {
            GuardContext ctx = GuardContextHolder.get();
            if (ctx == null || !ctx.planBool("breaker.failSoft", false)) {
                return false;
            }
            String diagnosticKey = safeBreakerKey(key);
            TraceStore.put("nightmare.breaker.failSoft.bypassed", true);
            TraceStore.put("nightmare.breaker.failSoft.key", diagnosticKey);
            TraceStore.put("nightmare.breaker.failSoft.remainingMs", Math.max(0L, remainingMs));
            TraceStore.put("nightmare.breaker.failSoft.openSinceMs", openAtMs);
            TraceStore.put("nightmare.breaker.failSoft.openUntilMs", openUntilMs);
            TraceStore.put("nightmare.breaker.failSoft.kind", String.valueOf(lastKind));
            TraceStore.inc("nightmare.breaker.failSoft.bypass.count");
            log.info("[NightmareBreaker] fail-soft bypass for open breaker key={} kind={} remainingMs={}",
                    diagnosticKey, lastKind, Math.max(0L, remainingMs));
            return true;
        } catch (Throwable ignored) {
            traceSuppressed("nightmare.failSoftBypass", ignored);
            return false;
        }
    }

    private NightmareBreakerProperties.EffectivePolicy policy(String key) {
        return policyCache.computeIfAbsent(key, props::policyFor);
    }

    private PolicySnapshot policySnapshot(String key) {
        return new PolicySnapshot(
                policy(key),
                props.isTripOnInterrupt(),
                props.isRateLimitCountsAsFailure(),
                props.isTimeoutCountsAsFailure(),
                props.getRateLimitOpenDuration(),
                props.getRateLimitMaxOpenDuration(),
                props.getTimeoutOpenDuration(),
                props.getTimeoutMaxOpenDuration(),
                props.getConfigOpenDuration(),
                props.getBackoffBase());
    }

    public boolean isOpen(String key) {
        return remainingOpenMs(key) > 0;
    }

    public boolean isOpen(String key, String stage) {
        return isOpen(key);
    }

    /**
     * Returns true if the circuit is OPEN or HALF_OPEN for the given key.
     * Useful for pre-call checks in query transformers and aux helpers.
     */
    public boolean isOpenOrHalfOpen(String key) {
        if (!props.isEnabled())
            return false;
        Gate gate = states.get(normalizeKey(key));
        if (gate != null) {
            GateState gateState = gate.state.get();
            return (gateState.mode == BreakerMode.OPEN && gateState.openUntilMs > nowMs())
                    || gateState.mode == BreakerMode.HALF_OPEN;
        }
        return false;
    }

    public boolean isOpenOrHalfOpen(String key, String stage) {
        return isOpenOrHalfOpen(key);
    }

    /**
     * Useful for orchestration gating without repeating null/loop checks.
     */
    public boolean isAnyOpen(String... keys) {
        if (!props.isEnabled() || keys == null || keys.length == 0) {
            return false;
        }
        for (String k : keys) {
            if (k == null)
                continue;
            if (isOpenOrHalfOpen(k))
                return true;
        }
        return false;
    }

    /**
     * Returns true if any breaker key in the current state map starts with the given prefix
     * and is OPEN.
     *
     * <p>We use this when the breaker key is parameterized (e.g. "chat-draft:<model>") and
     * higher-level orchestration only knows the logical prefix.</p>
     */
    public boolean isAnyOpenPrefix(String prefix) {
        if (!props.isEnabled() || prefix == null || prefix.isBlank()) {
            return false;
        }
        try {
            for (String k : states.keySet()) {
                if (k != null && k.startsWith(prefix) && isOpenOrHalfOpen(k)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            traceSuppressed("nightmare.prefixScan", e);
            tracePrefixScanFailure(prefix, e);
        }
        return false;
    }

    private static void tracePrefixScanFailure(String prefix, Throwable e) {
        TraceStore.put("nightmare.prefixScan.failed", true);
        TraceStore.put("nightmare.prefixScan.prefixHash", SafeRedactor.hashValue(prefix));
        TraceStore.put("nightmare.prefixScan.prefixLength", prefix == null ? 0 : prefix.length());
        TraceStore.put("nightmare.prefixScan.errorKind", String.valueOf(classify(e)));
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("nightmare.suppressed." + safeStage, true);
        TraceStore.put("nightmare.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    /**
     * Record HTTP 429 / rate-limit style failures in a uniform way.
     */
    @Deprecated(forRemoval = false)
    public void recordRateLimit(String key, String context, String reason) {
        recordRateLimit(key, context, null, reason, null);
    }

    /**
     * Record a RATE_LIMIT (ex: HTTP 429) with an optional retry-after / cooldown hint.
     *
     * <p>This is used to avoid breaker-poisoning (429 counted as generic failure) and to honor
     * Retry-After cooldowns when available.</p>
     */
    @Deprecated(forRemoval = false)
    public void recordRateLimit(String key, String context, String reason, Long retryAfterMs) {
        recordRateLimit(key, context, null, reason, retryAfterMs);
    }

    /**
     * Record a RATE_LIMIT (ex: HTTP 429) with an optional retry-after / cooldown hint, preserving the original error.
     */
    @Deprecated(forRemoval = false)
    public void recordRateLimit(String key, String context, Throwable error, String reason, Long retryAfterMs) {
        // Normalize early to keep trace keys stable.
        String k = (key == null) ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        String traceKey = safeBreakerKey(k);

        // COOLDOWN is a *local* gate (not a remote 429). We must not accumulate it into breaker
        // counters/backoff; otherwise OPEN propagation explodes and causes skip storms.
        String lowerReason = safeLower(reason);
        if (lowerReason.contains("cooldown")) {
            try {
                TraceStore.putIfAbsent("nightmare.rateLimit.cooldown." + traceKey, Boolean.TRUE);
                if (reason != null && !reason.isBlank()) {
                    TraceStore.putIfAbsent("nightmare.rateLimit.cooldown.reason." + traceKey, SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.rateLimitCooldownTrace", ignore);
            }
            return;
        }

        // Suppress duplicate RATE_LIMIT recording within the same request for the same key.
        // (e.g. HTTP_429 -> cooldown -> cooldown...) This prevents exponential backoff escalation.
        String onceKey = "nightmare.rateLimit.once." + traceKey;
        Object prev;
        try {
            prev = TraceStore.putIfAbsent(onceKey, Boolean.TRUE);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.rateLimitOnceTrace", ignore);
            prev = null;
        }
        if (prev != null) {
            try {
                TraceStore.inc("nightmare.rateLimit.dup." + traceKey);
                if (reason != null && !reason.isBlank()) {
                    TraceStore.put("nightmare.rateLimit.dup.lastReason." + traceKey, SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                }
            } catch (Throwable ignore) {
                traceSuppressed("nightmare.rateLimitDuplicateTrace", ignore);
            }
            return;
        }

        Throwable err = (error != null) ? error : (reason == null ? null : new RuntimeException(reason));
        recordFailure(k, FailureKind.RATE_LIMIT, err, context, retryAfterMs);
    }

    /**
     * Record HTTP 403 / rejected-style failures (bot detection, quota, etc).
     */
    @Deprecated(forRemoval = false)
    public void recordRejected(String key, String context, String reason) {
        recordFailure(key, FailureKind.REJECTED,
                (reason == null ? null : new RuntimeException(reason)), context);
    }

    /**
     * Record timeout-style failures.
     */
    @Deprecated(forRemoval = false)
    public void recordTimeout(String key, String context, String reason) {
        recordFailure(key, FailureKind.TIMEOUT,
                (reason == null ? null : new RuntimeException(reason)), context);
    }

    public long remainingOpenMs(String key) {
        if (!props.isEnabled())
            return 0;
        String base = normalizeKey(key);
        Gate gate = states.get(base);
        if (gate != null) {
            GateState gateState = gate.state.get();
            long remaining = gateState.mode == BreakerMode.OPEN
                    ? Math.max(0L, gateState.openUntilMs - nowMs()) : 0L;
            if (remaining > 0L) {
                recordOpenAtForTrace(base, gateState.openSinceMs, gateState.openUntilMs);
                recordOpenMetaForTrace(base, gateState.lastKind, gateState.lastErrorSummary);
            }
            return remaining;
        }
        return 0L;

    }

    public long remainingOpenMs(String key, String stage) {
        return remainingOpenMs(key);
    }

    private static String stageKey(String key, String stage) {
        String base = key == null ? "" : key.trim();
        if (base.isEmpty()) {
            return "";
        }
        String st = stage == null ? "" : stage.trim();
        if (st.isEmpty()) {
            return base;
        }
        String normalizedStage = st.replaceAll("[^a-zA-Z0-9._:-]+", "_");
        return base + ":" + normalizedStage;
    }

    /**
     * Debug/Probe용 상태 조회.
     * - 운영 로직에 영향 없이 현재 OPEN 여부/잔여시간/최근 실패 종류를 관찰한다.
     * - Probe/Soak/오케스트레이션 디버깅에서 '왜 우회(bypass)됐는지'를 재현 가능하게 한다.
     */
    public StateView inspect(String key) {
        if (key == null) {
            return new StateView(null, null, false, 0L, 0L, 0L, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
        String base = normalizeKey(key);
        Gate gate = states.get(base);
        if (gate != null) {
            GateState s = gate.state.get();
            long remain = s.mode == BreakerMode.OPEN ? Math.max(0L, s.openUntilMs - nowMs()) : 0L;
            return new StateView(key, s.mode, remain > 0L, s.openSinceMs, s.openUntilMs, remain,
                    s.lastKind, s.consecutiveFailures, s.consecutiveTimeouts,
                    s.consecutiveRateLimits, s.consecutiveRejected, s.consecutiveInterrupts,
                    s.consecutiveBlanks, s.consecutiveSilentFailures, s.consecutiveSlowCalls,
                    s.consecutiveSuccesses, s.halfOpenIssued, s.lastErrorSummary);
        }
        return new StateView(key, BreakerMode.CLOSED, false, 0L, 0L, 0L, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }
    /**
     * Debug/진단용 전체 스냅샷.
     * <p>
     * {@link #inspect(String)}는 단일 key만 조회하므로,
     * 운영 중 전체 복구/차단 상태를 한 화면에 보여주기 위해 제공한다.
     * </p>
     */
    public Map<String, StateView> snapshot() {
        // deterministic ordering helps diffs/ops.
        Map<String, StateView> out = new TreeMap<>();
        for (String key : states.keySet()) {
            out.put(key, inspect(key));
        }
        return out;
    }

    /**
     * 외부 노출용 가벼운 상태 스냅샷(불변).
     * Probe 응답에 그대로 넣어도 되는 수준의 메타만 포함한다.
     */
    public static final class StateView {
        public final String key;
        public final BreakerMode mode;
        public final boolean open;
        /**
         * The timestamp (epoch millis) when the breaker last transitioned into OPEN.
         * <p>
         * This is tracked in the breaker global state (not request-scoped observation).
         */
        public final long openSinceMs;
        public final long openUntilMs;
        public final long remainingMs;
        public final FailureKind lastKind;
        public final int consecutiveFailures;
        public final int consecutiveTimeouts;
        public final int consecutiveRateLimits;
        public final int consecutiveRejected;
        public final int consecutiveInterrupts;
        public final int consecutiveBlanks;
        public final int consecutiveSilentFailures;
        public final int consecutiveSlowCalls;
        public final int consecutiveSuccesses;
        public final int trialCalls;
        public final String lastErrorMessage;

        public StateView(String key,
                BreakerMode mode,
                boolean open,
                long openSinceMs,
                long openUntilMs,
                long remainingMs,
                FailureKind lastKind,
                int consecutiveFailures,
                int consecutiveTimeouts,
                int consecutiveRateLimits,
                int consecutiveRejected,
                int consecutiveInterrupts,
                int consecutiveBlanks,
                int consecutiveSilentFailures,
                int consecutiveSlowCalls,
                int consecutiveSuccesses,
                int trialCalls,
                String lastErrorMessage) {
            this.key = key;
            this.mode = mode;
            this.open = open;
            this.openSinceMs = openSinceMs;
            this.openUntilMs = openUntilMs;
            this.remainingMs = remainingMs;
            this.lastKind = lastKind;
            this.consecutiveFailures = consecutiveFailures;
            this.consecutiveTimeouts = consecutiveTimeouts;
            this.consecutiveRateLimits = consecutiveRateLimits;
            this.consecutiveRejected = consecutiveRejected;
            this.consecutiveInterrupts = consecutiveInterrupts;
            this.consecutiveBlanks = consecutiveBlanks;
            this.consecutiveSilentFailures = consecutiveSilentFailures;
            this.consecutiveSlowCalls = consecutiveSlowCalls;
            this.consecutiveSuccesses = consecutiveSuccesses;
            this.trialCalls = trialCalls;
            this.lastErrorMessage = lastErrorMessage;
        }
    }

    /**
     * An admission capability bound to one Gate identity and generation.  It intentionally
     * exposes no key or generation and can be completed exactly once from any thread.
     */
    public final class CallPermit {
        private final String key;
        private final Gate gate;
        private final long generation;
        private final boolean admitted;
        private final boolean trial;
        private final PolicySnapshot policySnapshot;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private CallPermit(String key, Gate gate, long generation, boolean admitted, boolean trial,
                PolicySnapshot policySnapshot) {
            this.key = key;
            this.gate = gate;
            this.generation = generation;
            this.admitted = admitted;
            this.trial = trial;
            this.policySnapshot = policySnapshot;
        }

        public void completeSuccess(long latencyMs) {
            finish(PermitOutcome.SUCCESS, null, null, latencyMs, null);
        }

        public void completeBlank(String context) {
            finish(PermitOutcome.BLANK, FailureKind.EMPTY_RESPONSE, null, 0L, null);
        }

        public void completeSilentFailure(String context, String reason) {
            finish(PermitOutcome.SILENT, FailureKind.EMPTY_RESPONSE, null, 0L, null);
        }

        public void completeFailure(FailureKind kind, Throwable error, String context) {
            completeFailure(kind, error, context, null);
        }

        public void completeFailure(FailureKind kind, Throwable error, String context, Long openDurationHintMs) {
            finish(PermitOutcome.FAILURE, normalizePermitKind(kind, error), error, 0L, openDurationHintMs);
        }

        public void completeRateLimit(String context, String reason, Long retryAfterMs) {
            completeRateLimit(context, null, reason, retryAfterMs);
        }

        public void completeRateLimit(String context, Throwable error, String reason, Long retryAfterMs) {
            finish(PermitOutcome.FAILURE, FailureKind.RATE_LIMIT, error, 0L, retryAfterMs);
        }

        public void completeRejected(String context, String reason) {
            finish(PermitOutcome.FAILURE, FailureKind.REJECTED, null, 0L, null);
        }

        public void completeTimeout(String context, String reason) {
            finish(PermitOutcome.FAILURE, FailureKind.TIMEOUT, null, 0L, null);
        }

        public void completeCancelled(Throwable error, String context) {
            finish(PermitOutcome.NEUTRAL, FailureKind.INTERRUPTED, error, 0L, null);
        }

        public void completeAbandoned(String context, String reason) {
            finish(PermitOutcome.NEUTRAL, null, null, 0L, null);
        }

        private void finish(PermitOutcome outcome, FailureKind kind, Throwable error,
                long latencyMs, Long hintMs) {
            if (!terminal.compareAndSet(false, true)) {
                try { TraceStore.inc("nightmare.permit.duplicateTerminal"); }
                catch (Throwable ignore) { traceSuppressed("nightmare.permitDuplicateTrace", ignore); }
                return;
            }
            finishPermit(this, outcome, kind, error, latencyMs, hintMs);
        }

        @Override
        public String toString() {
            return "CallPermit[opaque]";
        }
    }

    public CallPermit acquire(String key) {
        return acquire(key, null);
    }

    /** Stage is redacted diagnostics only; breaker ownership always remains on the base key. */
    public CallPermit acquire(String key, String stage) {
        String base = normalizeKey(key);
        if (!props.isEnabled() || base.isEmpty()) {
            return new CallPermit(null, null, -1L, false, false, null);
        }
        for (;;) {
            long now = nowMs();
            PolicySnapshot admissionPolicy = policySnapshot(base);
            Gate gate = states.computeIfAbsent(base,
                    ignored -> new Gate(GateState.closed(now, ticker.getAsLong())));
            GateState before = gate.state.get();
            if (before.retired) {
                states.remove(base, gate);
                continue;
            }
            NightmareBreakerProperties.EffectivePolicy cfg = admissionPolicy.effective();
            if (before.mode == BreakerMode.OPEN) {
                long remaining = before.openUntilMs - now;
                if (remaining > 0L) {
                    recordOpenAtForTrace(base, before.openSinceMs, before.openUntilMs);
                    recordOpenMetaForTrace(base, before.lastKind, before.lastErrorSummary);
                    if (isRequestFailSoftBypassEnabled(base, remaining, before.openSinceMs,
                            before.openUntilMs, before.lastKind)) {
                        publishSignal(base, SignalType.ADMISSION_BYPASSED, before);
                        return new CallPermit(base, gate, before.generation, false, false, admissionPolicy);
                    }
                    publishSignal(base, SignalType.ADMISSION_BLOCKED, before);
                    throw new OpenCircuitException(base, Duration.ofMillis(remaining), before.lastKind);
                }
                GateState after;
                if (cfg.halfOpenEnabled()) {
                    after = before.halfOpenFirstTrial(now, ticker.getAsLong(), admissionPolicy);
                } else {
                    after = before.closedAdmission(now, ticker.getAsLong(), true);
                }
                if (gate.state.compareAndSet(before, after)) {
                    if (after.mode == BreakerMode.HALF_OPEN) {
                        publishSignal(base, SignalType.HALF_OPEN_ENTERED, after);
                    }
                    return new CallPermit(base, gate, after.generation, true,
                            after.mode == BreakerMode.HALF_OPEN,
                            after.mode == BreakerMode.HALF_OPEN ? after.halfOpenPolicy : admissionPolicy);
                }
                continue;
            }
            if (before.mode == BreakerMode.HALF_OPEN) {
                PolicySnapshot cohortPolicy = before.halfOpenPolicy == null
                        ? admissionPolicy : before.halfOpenPolicy;
                int max = cohortPolicy.effective().halfOpenMaxCalls();
                if (before.halfOpenSealed || (max > 0 && before.halfOpenIssued >= max)) {
                    throw new OpenCircuitException(base, Duration.ZERO, before.lastKind);
                }
                GateState after = before.halfOpenAdmission(now, ticker.getAsLong());
                if (gate.state.compareAndSet(before, after)) {
                    return new CallPermit(base, gate, after.generation, true, true, cohortPolicy);
                }
                continue;
            }
            GateState after = before.closedAdmission(now, ticker.getAsLong(), false);
            if (gate.state.compareAndSet(before, after)) {
                return new CallPermit(base, gate, after.generation, true, false, admissionPolicy);
            }
        }
    }

    public void signalFailure(String key, FailureKind kind, Throwable error, String context) {
        signalFailure(key, kind, error, context, null);
    }

    public void signalFailure(String key, FailureKind kind, Throwable error, String context, Long hintMs) {
        signalOutcome(key, PermitOutcome.FAILURE, normalizePermitKind(kind, error), error, 0L, hintMs);
    }

    public void signalBlank(String key, String context) {
        signalOutcome(key, PermitOutcome.BLANK, FailureKind.EMPTY_RESPONSE, null, 0L, null);
        traceExternalSignal("blank", key, context, null);
    }

    public void signalSilentFailure(String key, String context, String reason) {
        signalOutcome(key, PermitOutcome.SILENT, FailureKind.EMPTY_RESPONSE, null, 0L, null);
        traceExternalSignal("silent", key, context, reason);
    }

    public void signalRateLimit(String key, String context, String reason, Long retryAfterMs) {
        signalRateLimit(key, context, null, reason, retryAfterMs);
    }

    public void signalRateLimit(String key, String context, Throwable error, String reason, Long retryAfterMs) {
        signalOutcome(key, PermitOutcome.FAILURE, FailureKind.RATE_LIMIT, error, 0L, retryAfterMs);
    }

    private void signalOutcome(String key, PermitOutcome outcome, FailureKind kind, Throwable error,
            long latencyMs, Long hintMs) {
        if (!props.isEnabled()) return;
        String base = normalizeKey(key);
        if (base.isEmpty()) return;
        long now = nowMs();
        PolicySnapshot snapshot = policySnapshot(base);
        for (;;) {
            Gate gate = states.computeIfAbsent(base,
                    ignored -> new Gate(GateState.closed(now, ticker.getAsLong())));
            FinishResult result = finishGate(base, gate, -1L, false, false, snapshot,
                    outcome, kind, error, latencyMs, hintMs);
            if (result != FinishResult.OWNER_LOST) return;
        }
    }

    private void traceExternalSignal(String signal, String key, String context, String reason) {
        if (!props.isEnabled() || signal == null) return;
        String base = normalizeKey(key);
        if (base.isEmpty()) return;
        try {
            String diagnosticKey = safeBreakerKey(base);
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("ts", nowMs());
            event.put("key", diagnosticKey);
            event.put("ctxLen", context == null ? 0 : context.length());
            if (reason != null && !reason.isBlank()) {
                event.put("reason", safeExternalSignalReason(reason));
            }
            TraceStore.put("nightmare." + signal + ".lastKey", diagnosticKey);
            TraceStore.put("nightmare." + signal + ".last", event);
            TraceStore.append("nightmare." + signal + ".events", event);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.externalSignalTrace", ignore);
        }
    }

    private void finishPermit(CallPermit permit, PermitOutcome outcome, FailureKind kind,
            Throwable error, long latencyMs, Long hintMs) {
        if (permit.gate == null || permit.key == null) return;
        finishGate(permit.key, permit.gate, permit.generation, permit.admitted,
                permit.trial, permit.policySnapshot, outcome, kind, error, latencyMs, hintMs);
    }

    private FinishResult finishGate(String key, Gate gate, long generation, boolean admitted, boolean trial,
            PolicySnapshot completionPolicy, PermitOutcome outcome, FailureKind kind,
            Throwable error, long latencyMs, Long hintMs) {
        for (;;) {
            if (states.get(key) != gate) return FinishResult.OWNER_LOST;
            GateState before = gate.state.get();
            if (before.retired) {
                states.remove(key, gate);
                return FinishResult.OWNER_LOST;
            }
            if (generation >= 0L && before.generation != generation) return FinishResult.IGNORED;
            PolicySnapshot snapshot = completionPolicy != null
                    ? completionPolicy
                    : (before.halfOpenPolicy != null ? before.halfOpenPolicy : policySnapshot(key));
            GateState after = evolve(before, snapshot, admitted, trial, outcome, kind,
                    error, latencyMs, hintMs);
            if (after == before) return FinishResult.IGNORED;
            if (gate.state.compareAndSet(before, after)) {
                if (before.mode != BreakerMode.OPEN && after.mode == BreakerMode.OPEN) {
                    emitOpenTransition(key, after);
                    publishSignal(key, SignalType.OPENED, after);
                } else if (before.mode == BreakerMode.OPEN && after.mode == BreakerMode.OPEN
                        && after.openUntilMs > before.openUntilMs) {
                    publishSignal(key, SignalType.OPEN_EXTENDED, after);
                } else if (before.mode == BreakerMode.HALF_OPEN && after.mode == BreakerMode.CLOSED) {
                    publishSignal(key, SignalType.CLOSED, after);
                }
                return FinishResult.APPLIED;
            }
        }
    }

    private GateState evolve(GateState before, PolicySnapshot policySnapshot,
            boolean admitted, boolean trial, PermitOutcome outcome, FailureKind kind,
            Throwable error, long latencyMs, Long hintMs) {
        NightmareBreakerProperties.EffectivePolicy cfg = policySnapshot.effective();
        long now = nowMs();
        GateState.Mutable next = before.mutable(now, ticker.getAsLong());
        if (admitted && next.inFlight > 0) next.inFlight--;
        if (outcome == PermitOutcome.NEUTRAL) {
            if (trial && next.mode == BreakerMode.HALF_OPEN && next.halfOpenIssued > 0) {
                next.halfOpenIssued--;
            }
            if (next.mode == BreakerMode.HALF_OPEN && next.halfOpenSealed && next.inFlight == 0) {
                next.closeFromHalfOpen();
            }
            return next.freeze();
        }
        if (outcome == PermitOutcome.SUCCESS) {
            if (!admitted || before.mode == BreakerMode.OPEN) return before;
            if (before.mode == BreakerMode.HALF_OPEN) {
                next.halfOpenSuccesses++;
                next.consecutiveSuccesses++;
                int target = Math.max(1, cfg.halfOpenSuccessThreshold());
                if (cfg.halfOpenMaxCalls() > 0) target = Math.min(target, cfg.halfOpenMaxCalls());
                if (next.halfOpenSuccesses >= target) next.halfOpenSealed = true;
                if (next.halfOpenSealed && next.inFlight == 0) next.closeFromHalfOpen();
                return next.freeze();
            }
            next.resetAdverse();
            if (cfg.tripOnSlowCall() && latencyMs >= cfg.slowCallThresholdMs()) {
                next.consecutiveSlowCalls++;
                if (next.consecutiveSlowCalls >= cfg.slowCallThreshold()) {
                    next.open(cfg.openDuration(), FailureKind.REJECTED, now);
                }
            } else next.consecutiveSlowCalls = 0;
            return next.freeze();
        }

        kind = kind == null ? FailureKind.UNKNOWN : kind;
        next.lastKind = kind;
        next.lastErrorSummary = redactedErrorSummary(error);
        boolean blank = outcome == PermitOutcome.BLANK;
        boolean silent = outcome == PermitOutcome.SILENT;
        if (blank) next.consecutiveBlanks++;
        else if (silent) next.consecutiveSilentFailures++;
        else {
            next.consecutiveBlanks = 0;
            next.consecutiveSilentFailures = 0;
            next.consecutiveSlowCalls = 0;
            if (countsAsFailureForThreshold(kind, policySnapshot)) next.consecutiveFailures++;
            if (kind == FailureKind.TIMEOUT) next.consecutiveTimeouts++;
            if (kind == FailureKind.RATE_LIMIT) next.consecutiveRateLimits++;
            if (kind == FailureKind.REJECTED || kind == FailureKind.CONFIG) next.consecutiveRejected++;
            if (kind == FailureKind.INTERRUPTED) next.consecutiveInterrupts++;
        }
        boolean trip = (blank && cfg.tripOnBlank() && next.consecutiveBlanks >= cfg.blankThreshold())
                || (silent && cfg.tripOnSilentFailure()
                        && next.consecutiveSilentFailures >= cfg.silentFailureThreshold())
                || (!blank && !silent && permitThresholdReached(next, policySnapshot, kind));
        if (before.mode == BreakerMode.HALF_OPEN
                && !(kind == FailureKind.INTERRUPTED && !policySnapshot.tripOnInterrupt())) trip = true;
        if (trip) {
            Duration duration = computePermitOpenDuration(policySnapshot, kind, next, hintMs);
            next.open(duration, kind, now);
        }
        return next.freeze();
    }

    private boolean permitThresholdReached(GateState.Mutable s,
            PolicySnapshot policySnapshot, FailureKind kind) {
        NightmareBreakerProperties.EffectivePolicy cfg = policySnapshot.effective();
        return (kind == FailureKind.TIMEOUT && s.consecutiveTimeouts >= cfg.timeoutThreshold())
                || (kind == FailureKind.RATE_LIMIT && s.consecutiveRateLimits >= cfg.rateLimitThreshold())
                || ((kind == FailureKind.REJECTED || kind == FailureKind.CONFIG)
                        && s.consecutiveRejected >= cfg.rejectedThreshold())
                || (policySnapshot.tripOnInterrupt() && kind == FailureKind.INTERRUPTED
                        && s.consecutiveInterrupts >= cfg.interruptThreshold())
                || (countsAsFailureForThreshold(kind, policySnapshot)
                        && s.consecutiveFailures >= cfg.failureThreshold());
    }

    private Duration computePermitOpenDuration(PolicySnapshot policySnapshot,
            FailureKind kind, GateState.Mutable state, Long hintMs) {
        NightmareBreakerProperties.EffectivePolicy cfg = policySnapshot.effective();
        Duration fallback = cfg.openDuration() == null ? Duration.ofSeconds(15) : cfg.openDuration();
        if (kind == FailureKind.RATE_LIMIT) {
            long base = safeToMs(policySnapshot.rateLimitOpenDuration(), safeToMs(fallback, 15_000L));
            if (hintMs != null && hintMs > 0L) base = Math.max(base, hintMs);
            long cap = safeToMs(policySnapshot.rateLimitMaxOpenDuration(), Math.max(base, 15_000L));
            return Duration.ofMillis(applyExponentialBackoff(base,
                    Math.max(1, state.consecutiveRateLimits), policySnapshot.backoffBase(), cap));
        }
        if (kind == FailureKind.TIMEOUT) {
            long base = safeToMs(policySnapshot.timeoutOpenDuration(), safeToMs(fallback, 15_000L));
            long cap = safeToMs(policySnapshot.timeoutMaxOpenDuration(), Math.max(base, 15_000L));
            if (hintMs != null && hintMs > 0L) { cap = Math.min(cap, hintMs); base = Math.min(base, cap); }
            return Duration.ofMillis(applyExponentialBackoff(base,
                    Math.max(1, state.consecutiveTimeouts), policySnapshot.backoffBase(), cap));
        }
        if (kind == FailureKind.CONFIG && policySnapshot.configOpenDuration() != null
                && policySnapshot.configOpenDuration().compareTo(fallback) > 0) {
            return policySnapshot.configOpenDuration();
        }
        return fallback;
    }

    private void emitOpenTransition(String key, GateState state) {
        String diagnosticKey = SafeRedactor.hashValue(key);
        recordOpenAtForTrace(key, state.openSinceMs, state.openUntilMs);
        recordOpenMetaForTrace(key, state.lastKind, state.lastErrorSummary);
        try {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("key", diagnosticKey);
            data.put("kind", String.valueOf(state.lastKind));
            data.put("openSinceMs", state.openSinceMs);
            data.put("openUntilMs", state.openUntilMs);
            emitEvent(DebugEventLevel.WARN, "nightmare.open." + diagnosticKey,
                    "NightmareBreaker OPEN", "NightmareBreaker.CallPermit", data, null);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.permitOpenEvent", ignore);
        }
    }

    private void publishSignal(String key, SignalType type, GateState state) {
        ApplicationEventPublisher publisher = this.eventPublisher;
        if (publisher == null || type == null || state == null) return;
        try {
            publisher.publishEvent(new StateSignal(
                    this,
                    stateSignalDiagnosticKey(key),
                    isWebSearchBreakerKey(key),
                    type,
                    state.mode,
                    state.lastKind,
                    state.openSinceMs,
                    state.openUntilMs));
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.stateSignal", ignore);
        }
    }

    private static boolean isWebSearchBreakerKey(String key) {
        return NightmareKeys.WEBSEARCH_NAVER.equals(key)
                || NightmareKeys.WEBSEARCH_BRAVE.equals(key)
                || NightmareKeys.WEBSEARCH_SERPAPI.equals(key)
                || NightmareKeys.WEBSEARCH_TAVILY.equals(key)
                || NightmareKeys.WEBSEARCH_HYBRID.equals(key);
    }

    private static String stateSignalDiagnosticKey(String key) {
        return SafeRedactor.hashValue(key);
    }

    private static String safeExternalSignalReason(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String value = reason.trim();
        if (value.matches("handler_exception:[A-Za-z][A-Za-z0-9_$]{0,63}")) {
            return value;
        }
        return switch (value) {
            case "dev_community_unverified_selected",
                    "official_only_starved",
                    "evidence_guard_no_info_with_evidence",
                    "definitive_failure_with_evidence",
                    "final_rescue" -> value;
            default -> SafeRedactor.hashValue(value);
        };
    }

    private static FailureKind normalizePermitKind(FailureKind kind, Throwable error) {
        if (kind != null && kind != FailureKind.UNKNOWN) return kind;
        FailureKind classified = classify(error);
        return classified == null ? FailureKind.UNKNOWN : classified;
    }

    private static String redactedErrorSummary(Throwable error) {
        if (error == null) return null;
        return error.getClass().getSimpleName() + ":" + SafeRedactor.hashValue(error.getMessage());
    }

    private long nowMs() {
        return clock.millis();
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim();
    }

    private enum PermitOutcome { SUCCESS, BLANK, SILENT, FAILURE, NEUTRAL }

    private enum FinishResult { APPLIED, IGNORED, OWNER_LOST }

    @Deprecated(forRemoval = false)
    public void checkOpenOrThrow(String key) {
        CallPermit permit = acquire(key, "legacy-check");
        permit.completeAbandoned("legacy-check", "split-lifecycle");
    }

    @Deprecated(forRemoval = false)
    public void recordSuccess(String key, long latencyMs) {
        try { TraceStore.inc("nightmare.legacy.success.telemetry"); }
        catch (Throwable ignore) { traceSuppressed("nightmare.legacySuccessTrace", ignore); }
    }

    @Deprecated(forRemoval = false)
    public void recordBlank(String key, String context) {
        signalBlank(key, context);
    }

    @Deprecated(forRemoval = false)
    public void recordSilentFailure(String key, String context, String reason) {
        signalSilentFailure(key, context, reason);
    }

    @Deprecated(forRemoval = false)
    public void recordFailure(String key, FailureKind kind, Throwable error, String context) {
        recordFailure(key, kind, error, context, null);
    }

    @Deprecated(forRemoval = false)
    public void recordFailure(String key, FailureKind kind, Throwable error, String context, Long openDurationHintMs) {
        signalFailure(key, kind, error, context, openDurationHintMs);
    }

    private static boolean countsAsFailureForThreshold(FailureKind kind, PolicySnapshot snapshot) {
        if (kind == null) return true;
        if (kind == FailureKind.INTERRUPTED) return false;
        if (kind == FailureKind.RATE_LIMIT) return snapshot.rateLimitCountsAsFailure();
        if (kind == FailureKind.TIMEOUT) return snapshot.timeoutCountsAsFailure();
        return true;
    }

    private static long safeToMs(Duration d, long defaultMs) {
        if (d == null) {
            return defaultMs;
        }
        try {
            long ms = d.toMillis();
            return ms > 0 ? ms : defaultMs;
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.safeToMs", ignore);
            return defaultMs;
        }
    }

    private static long applyExponentialBackoff(long baseMs, int attempt, double backoffBase, long capMs) {
        long base = Math.max(1L, baseMs);
        long cap = capMs > 0 ? capMs : Long.MAX_VALUE;

        int exp = Math.max(0, attempt - 1);
        double b = (backoffBase > 1.0d) ? backoffBase : 2.0d;

        double factor;
        try {
            factor = Math.pow(b, exp);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.backoffPow", ignore);
            factor = 1.0d;
        }

        double v = ((double) base) * factor;
        long ms;
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) {
            ms = base;
        } else if (v >= (double) Long.MAX_VALUE) {
            ms = Long.MAX_VALUE;
        } else {
            ms = (long) v;
        }

        if (ms < base) {
            ms = base;
        }
        if (ms > cap) {
            ms = cap;
        }
        return ms;
    }

    /**
     * 공통 실행 래퍼:
     * - open이면 fallback
     * - 실행 성공 시 recordSuccess / badResult면 recordBlank or recordSilentFailure
     * - 예외면 classify 후 recordFailure + fallback
     */
    public <T> T execute(String key,
            String context,
            Supplier<T> call,
            Predicate<T> isBadResult,
            Supplier<T> fallback) {
        if (!props.isEnabled()) {
            return call.get();
        }

        final CallPermit permit;
        try {
            permit = acquire(key, "execute");
        } catch (OpenCircuitException oce) {
            traceSuppressed("nightmare.executeOpenCircuitFallback", oce);
            return fallback != null ? fallback.get() : null;
        }

        long started = System.nanoTime();
        try {
            T out = call.get();
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;

            boolean bad = (isBadResult != null && isBadResult.test(out));
            if (bad) {
                if (out instanceof String s) {
                    if (s == null || s.isBlank()) {
                        permit.completeBlank(context);
                    } else {
                        permit.completeSilentFailure(context, "bad_result");
                    }
                } else {
                    permit.completeSilentFailure(context, "bad_result");
                }
            } else {
                permit.completeSuccess(latencyMs);
            }

            return out;
        } catch (Throwable t) {
            traceSuppressed("nightmare.executeFailureFallback", t);
            FailureKind kind = classify(t);

            if (kind == FailureKind.INTERRUPTED) {
                // Avoid poisoning pooled workers with lingering interrupt status.
                // NOTE: Interrupted is frequently a cancellation/teardown signal; do NOT count as TIMEOUT.
                Thread.interrupted();
                permit.completeCancelled(t, context);
            } else if (kind == FailureKind.RATE_LIMIT) {
                // Prefer recordRateLimit() so we can honor Retry-After hints and suppress duplicate 429 signals per request.
                Long retryAfterMs = tryExtractRetryAfterMs(t);
                String reason = "RATE_LIMIT";
                try {
                    if (t instanceof HttpException he && he.statusCode() == 429) {
                        reason = "HTTP 429";
                    } else if (t instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                        reason = "HTTP 429";
                    } else if (safeLower(t.getMessage()).contains("429")) {
                        reason = "HTTP 429";
                    }
                } catch (Throwable ignore) {
                    traceSuppressed("nightmare.executeRateLimitReason", ignore);
                }
                permit.completeRateLimit(context, t, reason, retryAfterMs);
            } else {
                permit.completeFailure(kind, t, context);
            }

            return fallback != null ? fallback.get() : null;
        }
    }

    public RuntimeException wrap(FailureKind kind, Throwable cause) {
        if (cause instanceof NightmareBreakException nbe)
            return nbe;
        return new NightmareBreakException(kind, cause);
    }


    @SuppressWarnings("unchecked")
    /**
     * Record breaker open timing into the request-local trace store.
     * <p>
     * We intentionally store the breaker global "openSince" (not the current/observed time),
     * so downstream (e.g., AuxBlockTracker) can render a consistent breakerOpenAt.
     */
    private void recordOpenAtForTrace(String key, long openSinceMs, long openUntilMs) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String traceKey = safeBreakerKey(key);
            Object val = TraceStore.get(TRACE_OPEN_AT_MS_KEY);
            Map<String, Long> byKey;
            if (val instanceof Map<?, ?> map) {
                byKey = (Map<String, Long>) map;
            } else {
                byKey = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_AT_MS_KEY, byKey);
            }
            byKey.putIfAbsent(traceKey, openSinceMs);

            Object untilVal = TraceStore.get(TRACE_OPEN_UNTIL_MS_KEY);
            Map<String, Long> byKeyUntil;
            if (untilVal instanceof Map<?, ?> map) {
                byKeyUntil = (Map<String, Long>) map;
            } else {
                byKeyUntil = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_UNTIL_MS_KEY, byKeyUntil);
            }
            // Keep the max(openUntil) per key; openUntil should not go backwards while OPEN,
            // but using max guards against any clock or ordering quirks.
            byKeyUntil.merge(traceKey, openUntilMs, Math::max);

            // Also keep the most recent open-until as a scalar (useful for quick debugging).
            TraceStore.put(TRACE_OPEN_UNTIL_MS_LAST_KEY, openUntilMs);
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.openAtTrace", ignore);
        }
    }

    @SuppressWarnings("unchecked")
    private void recordOpenMetaForTrace(String key, FailureKind kind, String errorMessage) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String traceKey = safeBreakerKey(key);
            Object kindObj = TraceStore.get(TRACE_OPEN_KIND_KEY);
            Map<String, String> kindMap;
            if (kindObj instanceof Map<?, ?> m) {
                kindMap = (Map<String, String>) m;
            } else {
                kindMap = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_KIND_KEY, kindMap);
            }
            kindMap.put(traceKey, (kind != null ? kind.name() : "UNKNOWN"));

            Object msgObj = TraceStore.get(TRACE_OPEN_ERRMSG_KEY);
            Map<String, String> msgMap;
            if (msgObj instanceof Map<?, ?> m2) {
                msgMap = (Map<String, String>) m2;
            } else {
                msgMap = new ConcurrentHashMap<>();
                TraceStore.put(TRACE_OPEN_ERRMSG_KEY, msgMap);
            }
            msgMap.put(traceKey, errorMessage == null
                    ? "none"
                    : SafeRedactor.hashValue(errorMessage));
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.openMetaTrace", ignore);
        }
    }


    private static String safeBreakerKey(String key) {
        if (key == null || key.isBlank()) return "unknown";
        return switch (key) {
            case NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                    NightmareKeys.DISAMBIGUATION_CLARIFY,
                    NightmareKeys.KEYWORD_SELECTION_SELECT,
                    NightmareKeys.RAG_CONTRADICTION_SCORE,
                    NightmareKeys.OVERDRIVE_CONTRADICTION_SCORER,
                    NightmareKeys.RAG_CHAIN_HANDLER,
                    NightmareKeys.RERANK_ONNX,
                    NightmareKeys.FAST_LLM_COMPLETE,
                    NightmareKeys.CHAT_DRAFT,
                    NightmareKeys.SELFASK_SEED,
                    NightmareKeys.SELFASK_FOLLOWUP,
                    NightmareKeys.WEBSEARCH_NAVER,
                    NightmareKeys.WEBSEARCH_BRAVE,
                    NightmareKeys.WEBSEARCH_SERPAPI,
                    NightmareKeys.WEBSEARCH_TAVILY,
                    NightmareKeys.WEBSEARCH_HYBRID,
                    NightmareKeys.WEB_FAILSOFT_STARVED,
                    NightmareKeys.WEB_FAILSOFT_MISROUTE,
                    NightmareKeys.RETRIEVAL_VECTOR,
                    NightmareKeys.RETRIEVAL_VECTOR_POISON,
                    NightmareKeys.BYPASS_ROUTING,
                    NightmareKeys.STRIKE_MODE -> key;
            default -> SafeRedactor.hashValue(key);
        };
    }

    private static String clip(String s, int max) {
        if (s == null || max <= 0)
            return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max)
            return t;
        return t.substring(0, max) + "...";
    }

    public static FailureKind classify(Throwable t) {
        Throwable root = unwrap(t);
        if (root == null)
            return FailureKind.UNKNOWN;

        if (root instanceof InterruptedException)
            return FailureKind.INTERRUPTED;
        if (root instanceof java.util.concurrent.CancellationException)
            return FailureKind.INTERRUPTED;

        // Reactor (or other libs) may use non-JDK cancel exception types.
        try {
            String cn = root.getClass().getName();
            if (cn != null) {
                String lcn = cn.toLowerCase();
                if (lcn.contains("cancel") && lcn.contains("exception")) {
                    return FailureKind.INTERRUPTED;
                }
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.classifyClassName", ignore);
        }
        if (root instanceof TimeoutException || root instanceof HttpTimeoutException)
            return FailureKind.TIMEOUT;

        if (root instanceof WebClientResponseException w) {
            int sc = w.getStatusCode().value();
            String wm = safeLower(w.getMessage());
            if (sc == 400 && isModelRequiredMsg(wm))
                return FailureKind.CONFIG;
            if (sc == 429)
                return FailureKind.RATE_LIMIT;
            if (sc == 503)
                return FailureKind.REJECTED;
            if (sc >= 400 && sc < 500)
                return FailureKind.HTTP_4XX;
            if (sc >= 500)
                return FailureKind.HTTP_5XX;
        }

        if (root instanceof HttpException he) {
            int sc = he.statusCode();
            String hm = safeLower(he.getMessage());
            if (sc == 400 && isModelRequiredMsg(hm))
                return FailureKind.CONFIG;
            if (sc == 400) {
                String m = safeLower(he.getMessage());
                if (m.contains("model is required")) {
                    return FailureKind.CONFIG;
                }
            }
            if (sc == 429)
                return FailureKind.RATE_LIMIT;
            if (sc == 503)
                return FailureKind.REJECTED; // 과부하/서버 불가용
            if (sc >= 400 && sc < 500)
                return FailureKind.HTTP_4XX;
            if (sc >= 500)
                return FailureKind.HTTP_5XX;
        }

        String msg = safeLower(root.getMessage());
        if (isModelRequiredMsg(msg))
            return FailureKind.CONFIG;
        if (msg.contains("rate") && msg.contains("limit"))
            return FailureKind.RATE_LIMIT;
        if (msg.contains("timeout") || msg.contains("timed out"))
            return FailureKind.TIMEOUT;
        if (msg.contains("interrupted"))
            return FailureKind.INTERRUPTED;
        if (msg.contains("cancel"))
            return FailureKind.INTERRUPTED;
        if (msg.contains("overloaded") || msg.contains("busy") || msg.contains("reject"))
            return FailureKind.REJECTED;
        return FailureKind.UNKNOWN;
    }



    /**
     * Best-effort extraction of Retry-After into milliseconds (capped).
     *
     * <p>Used to honor HTTP 429 backpressure hints when available.</p>
     */
    private static Long tryExtractRetryAfterMs(Throwable t) {
        try {
            Throwable root = unwrap(t);
            if (root instanceof WebClientResponseException w) {
                return parseRetryAfterMs(w.getHeaders());
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.retryAfterExtract", ignore);
        }
        return null;
    }

    private static Long parseRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String v = headers.getFirst("Retry-After");
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }

        // 1) delta-seconds
        boolean allDigits = true;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                long seconds = Long.parseLong(v);
                if (seconds <= 0) {
                    return 0L;
                }
                return Math.min(seconds * 1000L, 60_000L);
            } catch (NumberFormatException ignore) {
                traceSuppressed("nightmare.retryAfterSeconds", ignore);
                return null;
            }
        }

        // 2) HTTP-date (RFC 1123)
        try {
            java.time.ZonedDateTime dt = java.time.ZonedDateTime.parse(
                    v,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = java.time.Duration.between(java.time.Instant.now(), dt.toInstant()).toMillis();
            return Math.max(0L, Math.min(ms, 60_000L));
        } catch (Throwable ignore) {
            traceSuppressed("nightmare.retryAfterDate", ignore);
            return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t == null)
            return null;
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 12) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String safeLower(String s) {
        return (s == null) ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isModelRequiredMsg(String lowerMsg) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return false;
        }
        // Common OpenAI-compatible error bodies/messages
        if (lowerMsg.contains("model is required")) return true;
        if (lowerMsg.contains("must provide a model")) return true;
        if (lowerMsg.contains("model parameter") && lowerMsg.contains("required")) return true;
        if (lowerMsg.contains("missing required parameter") && lowerMsg.contains("model")) return true;
        return false;
    }

    private static final class Gate {
        final AtomicReference<GateState> state;

        Gate(GateState initial) {
            this.state = new AtomicReference<>(initial);
        }
    }

    /** Immutable CAS payload. Mutable is a private, unshared copy builder only. */
    private static final class GateState {
        final long generation;
        final BreakerMode mode;
        final boolean retired;
        final long openSinceMs;
        final long openUntilMs;
        final long lastActivityMs;
        final long lastActivityTick;
        final FailureKind lastKind;
        final String lastErrorSummary;
        final int consecutiveFailures;
        final int consecutiveTimeouts;
        final int consecutiveRateLimits;
        final int consecutiveRejected;
        final int consecutiveInterrupts;
        final int consecutiveBlanks;
        final int consecutiveSilentFailures;
        final int consecutiveSlowCalls;
        final int consecutiveSuccesses;
        final int inFlight;
        final int halfOpenIssued;
        final int halfOpenSuccesses;
        final boolean halfOpenSealed;
        final PolicySnapshot halfOpenPolicy;

        private GateState(Mutable m) {
            this.generation = m.generation;
            this.mode = m.mode;
            this.retired = m.retired;
            this.openSinceMs = m.openSinceMs;
            this.openUntilMs = m.openUntilMs;
            this.lastActivityMs = m.lastActivityMs;
            this.lastActivityTick = m.lastActivityTick;
            this.lastKind = m.lastKind;
            this.lastErrorSummary = m.lastErrorSummary;
            this.consecutiveFailures = m.consecutiveFailures;
            this.consecutiveTimeouts = m.consecutiveTimeouts;
            this.consecutiveRateLimits = m.consecutiveRateLimits;
            this.consecutiveRejected = m.consecutiveRejected;
            this.consecutiveInterrupts = m.consecutiveInterrupts;
            this.consecutiveBlanks = m.consecutiveBlanks;
            this.consecutiveSilentFailures = m.consecutiveSilentFailures;
            this.consecutiveSlowCalls = m.consecutiveSlowCalls;
            this.consecutiveSuccesses = m.consecutiveSuccesses;
            this.inFlight = m.inFlight;
            this.halfOpenIssued = m.halfOpenIssued;
            this.halfOpenSuccesses = m.halfOpenSuccesses;
            this.halfOpenSealed = m.halfOpenSealed;
            this.halfOpenPolicy = m.halfOpenPolicy;
        }

        static GateState closed(long now, long tick) {
            Mutable m = new Mutable();
            m.lastActivityMs = now;
            m.lastActivityTick = tick;
            return m.freeze();
        }

        Mutable mutable(long now, long tick) {
            Mutable m = new Mutable(this);
            m.lastActivityMs = now;
            m.lastActivityTick = tick;
            return m;
        }

        GateState closedAdmission(long now, long tick, boolean nextGeneration) {
            Mutable m = mutable(now, tick);
            if (nextGeneration) m.generation++;
            m.mode = BreakerMode.CLOSED;
            m.openSinceMs = 0L;
            m.openUntilMs = 0L;
            m.inFlight++;
            m.halfOpenIssued = 0;
            m.halfOpenSuccesses = 0;
            m.halfOpenSealed = false;
            m.halfOpenPolicy = null;
            return m.freeze();
        }

        GateState halfOpenFirstTrial(long now, long tick, PolicySnapshot cohortPolicy) {
            Mutable m = mutable(now, tick);
            m.generation++;
            m.mode = BreakerMode.HALF_OPEN;
            m.openSinceMs = 0L;
            m.openUntilMs = 0L;
            m.inFlight = 1;
            m.halfOpenIssued = 1;
            m.halfOpenSuccesses = 0;
            m.halfOpenSealed = false;
            m.halfOpenPolicy = cohortPolicy;
            m.consecutiveSuccesses = 0;
            return m.freeze();
        }

        GateState halfOpenAdmission(long now, long tick) {
            Mutable m = mutable(now, tick);
            m.inFlight++;
            m.halfOpenIssued++;
            return m.freeze();
        }

        GateState retired(long now, long tick) {
            Mutable m = mutable(now, tick);
            m.generation++;
            m.retired = true;
            return m.freeze();
        }

        private static final class Mutable {
            long generation;
            BreakerMode mode = BreakerMode.CLOSED;
            boolean retired;
            long openSinceMs;
            long openUntilMs;
            long lastActivityMs;
            long lastActivityTick;
            FailureKind lastKind = FailureKind.UNKNOWN;
            String lastErrorSummary;
            int consecutiveFailures;
            int consecutiveTimeouts;
            int consecutiveRateLimits;
            int consecutiveRejected;
            int consecutiveInterrupts;
            int consecutiveBlanks;
            int consecutiveSilentFailures;
            int consecutiveSlowCalls;
            int consecutiveSuccesses;
            int inFlight;
            int halfOpenIssued;
            int halfOpenSuccesses;
            boolean halfOpenSealed;
            PolicySnapshot halfOpenPolicy;

            Mutable() {}

            Mutable(GateState s) {
                generation = s.generation;
                mode = s.mode;
                retired = s.retired;
                openSinceMs = s.openSinceMs;
                openUntilMs = s.openUntilMs;
                lastActivityMs = s.lastActivityMs;
                lastActivityTick = s.lastActivityTick;
                lastKind = s.lastKind;
                lastErrorSummary = s.lastErrorSummary;
                consecutiveFailures = s.consecutiveFailures;
                consecutiveTimeouts = s.consecutiveTimeouts;
                consecutiveRateLimits = s.consecutiveRateLimits;
                consecutiveRejected = s.consecutiveRejected;
                consecutiveInterrupts = s.consecutiveInterrupts;
                consecutiveBlanks = s.consecutiveBlanks;
                consecutiveSilentFailures = s.consecutiveSilentFailures;
                consecutiveSlowCalls = s.consecutiveSlowCalls;
                consecutiveSuccesses = s.consecutiveSuccesses;
                inFlight = s.inFlight;
                halfOpenIssued = s.halfOpenIssued;
                halfOpenSuccesses = s.halfOpenSuccesses;
                halfOpenSealed = s.halfOpenSealed;
                halfOpenPolicy = s.halfOpenPolicy;
            }

            void resetAdverse() {
                consecutiveFailures = 0;
                consecutiveTimeouts = 0;
                consecutiveRateLimits = 0;
                consecutiveRejected = 0;
                consecutiveInterrupts = 0;
                consecutiveBlanks = 0;
                consecutiveSilentFailures = 0;
            }

            void closeFromHalfOpen() {
                generation++;
                mode = BreakerMode.CLOSED;
                openSinceMs = 0L;
                openUntilMs = 0L;
                inFlight = 0;
                halfOpenIssued = 0;
                halfOpenSuccesses = 0;
                halfOpenSealed = false;
                halfOpenPolicy = null;
                resetAdverse();
            }

            void open(Duration duration, FailureKind kind, long now) {
                boolean alreadyOpen = mode == BreakerMode.OPEN && openUntilMs > now;
                if (!alreadyOpen) {
                    generation++;
                    openSinceMs = now;
                }
                mode = BreakerMode.OPEN;
                long millis = duration == null ? 15_000L : Math.max(1L, duration.toMillis());
                long candidate = saturatedAdd(now, millis);
                openUntilMs = alreadyOpen ? Math.max(openUntilMs, candidate) : candidate;
                lastKind = kind == null ? FailureKind.UNKNOWN : kind;
                inFlight = 0;
                halfOpenIssued = 0;
                halfOpenSuccesses = 0;
                halfOpenSealed = false;
                halfOpenPolicy = null;
                consecutiveSuccesses = 0;
            }

            GateState freeze() { return new GateState(this); }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }


    public static class NightmareBreakException extends RuntimeException {
        private final FailureKind kind;

        public NightmareBreakException(FailureKind kind, Throwable cause) {
            super("NightmareBreak: " + kind
                    + (cause != null ? String.format(": errorHash=%s errorLength=%d",
                            SafeRedactor.hashValue(String.valueOf(cause)), String.valueOf(cause).length()) : ""), cause);
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }

    public static class OpenCircuitException extends RuntimeException {
        private final String key;
        private final Duration remaining;
        private final FailureKind lastKind;

        public OpenCircuitException(String key, Duration remaining, FailureKind lastKind) {
            super("NightmareBreaker is OPEN: key=" + safeBreakerKey(key) + ", remaining=" + remaining);
            this.key = key;
            this.remaining = remaining;
            this.lastKind = lastKind;
        }

        public String key() {
            return key;
        }

        public Duration remaining() {
            return remaining;
        }

        public FailureKind lastKind() {
            return lastKind;
        }
    }
}
