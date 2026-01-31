package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, fail-soft query augmentation.
 *
 * <p>
 * Motivation: when the LLM-based query planner/transformer is bypassed (often due to
 * auxDegraded/compression), the pipeline may fall back to a single query.
 * That can severely reduce web recall and lead to citation starvation.
 *
 * <p>
 * This aspect augments a single query into a small burst of rule-based alternatives
 * (no LLM calls) right before retrieval.
 */
@Aspect
public class FailSoftQueryAugmentAspect {

    private static final Logger log = LoggerFactory.getLogger(FailSoftQueryAugmentAspect.class);

    private static final ThreadLocal<Boolean> REENTRY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Environment env;
    private final RuleBasedQueryAugmenter augmenter;

    public FailSoftQueryAugmentAspect(Environment env, RuleBasedQueryAugmenter augmenter) {
        this.env = env;
        this.augmenter = augmenter;
    }

    @Around("execution(* com.example.lms.service.rag.HybridRetriever.retrieveAll(..))")
    public Object aroundRetrieveAll(ProceedingJoinPoint pjp) throws Throwable {
        if (Boolean.TRUE.equals(REENTRY.get())) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return pjp.proceed();
        }

        if (!(args[0] instanceof List<?> rawQueries)) {
            return pjp.proceed();
        }

        boolean enabled = env.getProperty("nova.orch.failsoft-query-augment.enabled", Boolean.class, Boolean.TRUE);
        if (!enabled) {
            return pjp.proceed();
        }

        int maxQueries = Math.max(1,
                env.getProperty("nova.orch.failsoft-query-augment.maxQueries", Integer.class, 3));
        boolean onlyWhenSingle = env.getProperty("nova.orch.failsoft-query-augment.onlyWhenSingleQuery", Boolean.class,
                Boolean.TRUE);
        boolean requireAllowWeb = env.getProperty("nova.orch.failsoft-query-augment.requireAllowWeb", Boolean.class,
                Boolean.TRUE);
        boolean skipWhenRateLimited = env.getProperty("nova.orch.failsoft-query-augment.skipWhenRateLimited",
                Boolean.class, Boolean.TRUE);

        if (onlyWhenSingle && rawQueries.size() > 1) {
            return pjp.proceed();
        }
        if (rawQueries.isEmpty()) {
            return pjp.proceed();
        }
        if (rawQueries.size() >= maxQueries) {
            return pjp.proceed();
        }

        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.get();
        } catch (Throwable ignore) {
            ctx = null;
        }

        // Only activate when we have strong signals that the planner/transformer was
        // bypassed/degraded.
        boolean shouldActivate = false;
        String activateReason = null;
        if (ctx != null) {
            if (ctx.isAuxDown() || ctx.isCompressionMode() || ctx.isStrikeMode() || ctx.isBypassMode()) {
                shouldActivate = true;
                activateReason = ctx.isAuxHardDown() ? "auxHardDown"
                        : (ctx.isAuxDegraded() ? "auxDegraded"
                                : (ctx.isCompressionMode() ? "compression"
                                        : (ctx.isStrikeMode() ? "strike" : "bypass")));
            }
        }

        // MERGE_HOOK:PROJ_AGENT::FAILSOFT_QUERYAUGMENT_QT_DEGRADED_V1
        // QueryTransformer can be bypassed as a cheap degradation (breaker-open, stage clamp) without
        // flipping request-scoped aux flags. In that case, still run deterministic augmentation.
        boolean qtDegraded = truthy(TraceStore.get("aux.queryTransformer.degraded"))
                || truthy(TraceStore.get("qtx.bypass"));
        boolean qtBlocked = truthy(TraceStore.get("aux.queryTransformer.blocked"));
        if (!shouldActivate && (qtDegraded || qtBlocked)) {
            shouldActivate = true;
            activateReason = qtDegraded ? "qt_degraded" : "qt_blocked";
        }

        if (!shouldActivate) {
            return pjp.proceed();
        }

        if (skipWhenRateLimited && ctx != null && ctx.isWebRateLimited()) {
            TraceStore.put("orch.failsoft.queryAugment.skipped", "webRateLimited");
            return pjp.proceed();
        }

        // Best-effort: respect allowWeb from metaHints if present.
        if (requireAllowWeb) {
            Map<String, Object> metaHints = null;
            if (args.length >= 4 && args[3] instanceof Map<?, ?> m) {
                try {
                    //noinspection unchecked
                    metaHints = (Map<String, Object>) m;
                } catch (Exception ignore) {
                    metaHints = null;
                }
            }
            if (metaHints != null) {
                Object allowWeb = metaHints.get("allowWeb");
                if (allowWeb != null) {
                    String s = String.valueOf(allowWeb).trim().toLowerCase(Locale.ROOT);
                    boolean ok = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
                    if (!ok) {
                        TraceStore.put("orch.failsoft.queryAugment.skipped", "allowWeb=false");
                        return pjp.proceed();
                    }
                }
            }
        }

        // Pick the first non-blank query as seed.
        String seed = null;
        for (Object q : rawQueries) {
            if (q == null) continue;
            String s = String.valueOf(q).trim();
            if (!s.isBlank()) {
                seed = s;
                break;
            }
        }
        if (seed == null || seed.isBlank()) {
            return pjp.proceed();
        }

        RuleBasedQueryAugmenter.Augment aug;
        try {
            aug = (ctx == null) ? augmenter.augment(seed) : augmenter.augment(seed, ctx);
        } catch (Exception e) {
            log.debug("[nova][failsoft-query-augment] augment failed: {}", e.toString());
            return pjp.proceed();
        }

        if (aug == null || aug.queries() == null || aug.queries().isEmpty()) {
            return pjp.proceed();
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (Object q : rawQueries) {
            if (q == null) continue;
            String s = String.valueOf(q).trim();
            if (!s.isBlank()) merged.add(s);
        }
        for (String q : aug.queries()) {
            if (q == null) continue;
            String s = q.trim();
            if (s.isBlank()) continue;
            merged.add(s);
            if (merged.size() >= maxQueries) break;
        }

        if (merged.size() <= rawQueries.size()) {
            return pjp.proceed();
        }

        List<String> out = new ArrayList<>(merged);

        try {
            TraceStore.put("orch.failsoft.queryAugment.used", true);
            TraceStore.put("orch.failsoft.queryAugment.reason",
                    activateReason != null ? activateReason
                            : (ctx == null ? "unknown"
                                    : (ctx.isAuxHardDown() ? "auxHardDown"
                                            : (ctx.isAuxDegraded() ? "auxDegraded"
                                                    : (ctx.isCompressionMode() ? "compression"
                                                            : (ctx.isStrikeMode() ? "strike" : "bypass"))))));
            TraceStore.put("orch.failsoft.queryAugment.before", rawQueries.size());
            TraceStore.put("orch.failsoft.queryAugment.after", out.size());
            TraceStore.put("orch.failsoft.queryAugment.sample", out.size() <= 3 ? out : out.subList(0, 3));
            if (aug.intent() != null) {
                TraceStore.put("orch.failsoft.queryAugment.intent", aug.intent().name());
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        Object[] newArgs = args.clone();
        newArgs[0] = out;

        REENTRY.set(Boolean.TRUE);
        try {
            return pjp.proceed(newArgs);
        } finally {
            REENTRY.set(Boolean.FALSE);
        }
    }


    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b.booleanValue();
        }
        if (v instanceof Number n) {
            return n.longValue() != 0L;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return false;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "null".equalsIgnoreCase(s)) {
            return false;
        }
        return true;
    }
}
