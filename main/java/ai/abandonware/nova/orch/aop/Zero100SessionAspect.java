package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.timebudget.TimeBudgetGuard;
import ai.abandonware.nova.orch.zero100.Zero100BranchScheduler;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Locale;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class Zero100SessionAspect {

    private static final Logger log = LoggerFactory.getLogger(Zero100SessionAspect.class);

    private final Zero100EngineProperties props;
    private final Zero100SessionRegistry registry;
    @SuppressWarnings("unused")
    private final Environment env;

    public Zero100SessionAspect(Zero100EngineProperties props, Zero100SessionRegistry registry, Environment env) {
        this.props = props;
        this.registry = registry;
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.ChatService.continueChat(..)) || execution(* com.example.lms.service.ChatService.ask(..))")
    public Object aroundChatEntry(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEngineEnabled()) {
            return pjp.proceed();
        }

        String msg = extractMessage(pjp.getArgs());
        GuardContext gc = null;
        try {
            gc = GuardContextHolder.get();
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("zero100.guardContextLookup", ignore);
            gc = null;
        }

        boolean enabled = isZero100Enabled(gc, msg);
        if (!enabled) {
            return pjp.proceed();
        }

        long requestStartedNs = System.nanoTime();
        long maxMinutes = (gc != null) ? gc.planLong("search.zero100.maxMinutes", props.getMaxMinutes()) : props.getMaxMinutes();
        long sliceMs = (gc != null) ? gc.planLong("search.zero100.sliceMs", props.getSliceMs()) : props.getSliceMs();
        long webTimeboxMs = (gc != null) ? gc.planLong("search.zero100.webTimeboxMs", props.getWebCallTimeboxMs()) : props.getWebCallTimeboxMs();
        long backoffHardCapMs = (gc != null) ? gc.planLong("search.zero100.backoffHardCapMs", props.getBackoffHardCapMs()) : props.getBackoffHardCapMs();

        String sid = "unknown";
        try {
            sid = LogCorrelation.sessionId();
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("zero100.sessionIdLookup", ignore);
            sid = "unknown";
        }

        Zero100SessionRegistry.Slice slice = null;
        Zero100BranchScheduler.Schedule schedule = null;
        try {
            slice = registry.touch(
                    sid,
                    (gc != null && gc.getUserQuery() != null && !gc.getUserQuery().isBlank()) ? gc.getUserQuery() : msg,
                    maxMinutes,
                    sliceMs,
                    webTimeboxMs,
                    backoffHardCapMs);
        } catch (Throwable t) {
            // fail-soft: never block the pipeline
            log.debug("[Zero100] registry.touch failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            slice = null;
        }

        try {
            TraceStore.put("zero100.enabled", true);
            TraceStore.put("zero100.sessionId", SafeRedactor.hashValue(sid));
            if (slice != null) {
                putMpIntentDiagnostics(slice.getMpIntent());
                TraceStore.put("zero100.slice.idx", slice.getSliceIndex());
                TraceStore.put("zero100.slice.ms", slice.getSliceMs());
                TraceStore.put("zero100.remainingMs", slice.remainingSessionMs());
                TraceStore.put("zero100.clampMode", String.valueOf(slice.getClampMode()));
                TraceStore.put("zero100.explorationRate", slice.getExplorationRate());
                TraceStore.put("zero100.web.timeboxMs", slice.getWebTimeboxMs());
                TraceStore.put("zero100.backoff.hardCapMs", slice.getBackoffHardCapMs());
                schedule = Zero100BranchScheduler.schedule(slice, gc);
                TraceStore.put("zero100.phase", schedule.phase().name());
                TraceStore.put("zero100.progressPct", schedule.progressPct());
                TraceStore.put("zero100.activeLane", schedule.activeLane());
                TraceStore.put("zero100.branch.weights", schedule.laneWeights());
                TraceStore.put("zero100.branch.callRatios", schedule.callBudgetRatios());
                TraceStore.put("zero100.branch.timeboxMs", schedule.laneTimeboxesMs());
                TraceStore.put("zero100.branch.routingProbabilities", schedule.routingProbabilities());
                TraceStore.put("zero100.timeBudget.latencyPenalties", schedule.latencyPenalties());
                TraceStore.put("zero100.timeBudget.budgetDebitsMs", schedule.budgetDebitsMs());
                TraceStore.put("zero100.timeBudget.forceFallback", schedule.forceFallback());
                TraceStore.put("zero100.timeBudget.cancelSignal", schedule.forceFallback());
                if (schedule.forceFallback()) {
                    TraceStore.put("zero100.timeBudget.forceFallback.reason", "previous_branch_penalty");
                }
                TraceStore.put("zero100.queryBurst.max", schedule.queryBurstMax());
                TraceStore.put("zero100.crossVerify.required", schedule.crossVerifyRequired());
                TraceStore.put("zero100.consensus.enabled", schedule.consensusEnabled());
                TraceStore.put("zero100.riskConsensus.enabled", schedule.riskConsensusEnabled());
                TraceStore.put("zero100.riskConsensus.minLaneCoverage", schedule.minLaneCoverage());
                TraceStore.put("zero100.riskConsensus.riskPenaltyLambda", schedule.riskPenaltyLambda());
                TraceStore.put("zero100.riskConsensus.minRrfWeight", schedule.minRrfWeight());
                TraceStore.put("zero100.riskConsensus.maxRrfWeight", schedule.maxRrfWeight());
                log.debug("[Zero100][3lane] phase={} activeLane={} callRatios={} timeboxesMs={}",
                        schedule.phase(), schedule.activeLane(), schedule.callBudgetRatios(), schedule.laneTimeboxesMs());
            }
        } catch (Throwable t) {
            TraceStore.put("zero100.scheduler.failureClass", "zero100_scheduler_failed");
            // best-effort
        }

        try {
            return pjp.proceed();
        } finally {
            recordTimeBudgetFeedback(sid, slice, schedule, requestStartedNs);
        }
    }

    private void recordTimeBudgetFeedback(
            String sid,
            Zero100SessionRegistry.Slice slice,
            Zero100BranchScheduler.Schedule schedule,
            long requestStartedNs) {
        if (slice == null || schedule == null || registry == null) {
            return;
        }
        try {
            long elapsedMs = Math.max(0L, (System.nanoTime() - requestStartedNs) / 1_000_000L);
            String lane = schedule.activeLane();
            long expectedMs = mapLong(schedule.laneTimeboxesMs(), lane, Math.max(50L, slice.getWebTimeboxMs()));
            long budgetMs = Math.max(1L, slice.getWebTimeboxMs());
            long remainingMs = Math.max(0L, Math.min(slice.remainingSessionMs(), budgetMs - elapsedMs));
            TimeBudgetGuard.Signals signals = TimeBudgetGuard.Signals.builder()
                    .timeoutCount(traceCount("timeout") + (traceBool("zero100.webTimebox.hit", false) ? 1 : 0))
                    .rateLimitCount(traceCount("rate_limit") + traceCount("rate-limit") + traceCount("429"))
                    .qtxSoftCooldownActive(traceBool("qtx.softCooldown.active", false))
                    .qtxSoftCooldownRemainingMs(traceLong("qtx.softCooldown.remainingMs", 0L))
                    .remainingMs(remainingMs)
                    .providerCoolingDown(traceContains("cooldown"))
                    .breakerOpen(traceContains("breaker_open"))
                    .build();
            TimeBudgetGuard.Decision decision = TimeBudgetGuard.evaluate(
                    lane,
                    elapsedMs,
                    expectedMs,
                    budgetMs,
                    qualityScoreFromTrace(),
                    signals);
            registry.recordBudgetFeedback(sid, lane, decision);
            traceDecision(decision);
        } catch (IllegalStateException | ClassCastException | NullPointerException t) {
            TraceStore.put("zero100.timeBudget.failureClass", "time_budget_feedback_failed");
            log.debug("[Zero100][timeBudget] feedback fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
        }
    }

    private static void traceDecision(TimeBudgetGuard.Decision decision) {
        if (decision == null) {
            return;
        }
        TraceStore.put("zero100.timeBudget.activeLane", decision.lane());
        TraceStore.put("zero100.timeBudget.elapsedMs", decision.elapsedMs());
        TraceStore.put("zero100.timeBudget.expectedMs", decision.expectedMs());
        TraceStore.put("zero100.timeBudget.budgetMs", decision.budgetMs());
        TraceStore.put("zero100.timeBudget.qualityScore", decision.qualityScore());
        TraceStore.put("zero100.timeBudget.overrunLogit", decision.overrunLogit());
        TraceStore.put("zero100.timeBudget.latencyPenalty", decision.latencyPenalty());
        TraceStore.put("zero100.timeBudget.budgetDebitMs", decision.budgetDebitMs());
        TraceStore.put("zero100.timeBudget.routeMultiplier", decision.routeMultiplier());
        TraceStore.put("zero100.timeBudget.forceFallback", decision.forceFallback());
        TraceStore.put("zero100.timeBudget.cancelSignal", decision.forceFallback());
        TraceStore.put("zero100.timeBudget.reason", decision.reason());
        if (decision.forceFallback()) {
            TraceStore.put("zero100.timeBudget.forceFallback.reason", decision.reason());
        }
    }

    private static void putMpIntentDiagnostics(String mpIntent) {
        boolean present = mpIntent != null && !mpIntent.trim().isEmpty();
        TraceStore.put("zero100.mpIntent.present", present);
        TraceStore.put("zero100.mpIntent.lengthBucket", lengthBucket(mpIntent));
        if (present) {
            TraceStore.put("zero100.mpIntent.hash12", SafeRedactor.hash12(mpIntent));
        }
    }

    private static String lengthBucket(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "0";
        }
        int len = value.length();
        if (len <= 32) {
            return "1-32";
        }
        if (len <= 128) {
            return "33-128";
        }
        if (len <= 380) {
            return "129-380";
        }
        return "381+";
    }

    private static boolean isZero100Enabled(GuardContext gc, String msg) {
        try {
            if (gc != null && gc.planBool("search.zero100.enabled", false)) {
                return true;
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("zero100.enabledPlanOverride", ignore);
        }

        // Direct plan ids (avoid PlanHintApplier's contains("zero") alias trap by using the .v1 form)
        try {
            String pid = (gc == null) ? null : gc.getPlanId();
            if (pid != null) {
                String p = pid.trim().toLowerCase(Locale.ROOT);
                if (p.equals("zero100.v1") || p.equals("emperor_zero100.v1") || p.equals("emperor-pro.v1")) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("zero100.planIdLookup", ignore);
        }

        return looksLikeZero100Hint(msg);
    }

    private static boolean looksLikeZero100Hint(String msg) {
        if (msg == null) return false;
        String s = msg.trim();
        if (s.isEmpty()) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("zero-100")
                || lower.contains("zero100")
                || lower.contains("#zero100")
                || lower.contains("제로백")
                || lower.contains("엠페러")
                || lower.contains("emperor time")
                || lower.contains("emperor-time");
    }

    private static boolean traceBool(String key, boolean fallback) {
        Object value = TraceStore.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
            return false;
        }
        return fallback;
    }

    private static long traceLong(String key, long fallback) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                WebFailSoftTraceSuppressions.trace("zero100.traceLongParse",
                        new NumberFormatException("non-finite number"));
                return fallback;
            }
            return n.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            WebFailSoftTraceSuppressions.trace("zero100.traceLongParse", ignored);
            return fallback;
        }
    }

    private static int traceCount(String needle) {
        if (needle == null || needle.isBlank()) {
            return 0;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        int count = 0;
        for (Object value : TraceStore.getAll().values()) {
            if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(n)) {
                count++;
            }
        }
        return count;
    }

    private static boolean traceContains(String needle) {
        return traceCount(needle) > 0;
    }

    private static double qualityScoreFromTrace() {
        Object normalized = TraceStore.get("rag.eval.normalized");
        if (normalized instanceof Map<?, ?> map) {
            double value = mapDouble(map, "balancedScore", Double.NaN);
            if (Double.isFinite(value)) {
                return value;
            }
        }
        Object scorecard = TraceStore.get("rag.eval.scorecard");
        if (scorecard instanceof Map<?, ?> map) {
            double value = mapDouble(map, "score", Double.NaN);
            if (Double.isFinite(value)) {
                return value;
            }
        }
        return 1.0d;
    }

    private static long mapLong(Map<String, Long> map, String key, long fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Long value = map.get(key);
        return value == null ? fallback : value;
    }

    private static double mapDouble(Map<?, ?> map, String key, double fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                WebFailSoftTraceSuppressions.trace("zero100.mapDoubleParse",
                        new NumberFormatException("non-finite number"));
                return fallback;
            }
            return numeric;
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            WebFailSoftTraceSuppressions.trace("zero100.mapDoubleParse", ignored);
            return fallback;
        }
    }

    private static String extractMessage(Object[] args) {
        if (args == null) return "";
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof ChatRequestDto dto) {
                String v = dto.getMessage();
                return (v == null) ? "" : v;
            }
            if (a instanceof String s) {
                return s;
            }
        }
        return "";
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
