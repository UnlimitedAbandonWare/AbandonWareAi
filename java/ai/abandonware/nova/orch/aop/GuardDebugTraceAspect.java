package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Guard / orchestration debug tracer.
 *
 * <p>Goal: when keyword-selection / query-transformer fail and GuardContext flips to BYPASS,
 * print the exact triggering conditions and breaker states.</p>
 *
 * <p>Enabled only when {@code nova.orch.debug.guard-trace.enabled=true}.</p>
 */
@Aspect
public class GuardDebugTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(GuardDebugTraceAspect.class);

    private final Environment env;
    private final ObjectProvider<NightmareBreaker> nightmareBreakerProvider;

    public GuardDebugTraceAspect(Environment env, ObjectProvider<NightmareBreaker> nightmareBreakerProvider) {
        this.env = env;
        this.nightmareBreakerProvider = nightmareBreakerProvider;
    }

    @Around("execution(* com.example.lms.search.KeywordSelectionService.select(..))")
    public Object aroundKeywordSelection(ProceedingJoinPoint pjp) throws Throwable {
        return traceStage("keyword-selection", pjp);
    }

    @Around("execution(* com.example.lms.transform.QueryTransformer.transformEnhanced(..))")
    public Object aroundQueryTransformer(ProceedingJoinPoint pjp) throws Throwable {
        return traceStage("query-transformer", pjp);
    }

    @Around("execution(* com.example.lms.service.disambiguation.QueryDisambiguationService.clarify(..))")
    public Object aroundDisambiguation(ProceedingJoinPoint pjp) throws Throwable {
        return traceStage("disambiguation", pjp);
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundChatWorkflow(ProceedingJoinPoint pjp) throws Throwable {
        return traceStage("chat-workflow", pjp);
    }

    private Object traceStage(String stage, ProceedingJoinPoint pjp) throws Throwable {
        if (!isEnabled()) {
            return pjp.proceed();
        }

        String query = extractQuery(pjp.getArgs());
        GuardSnapshot before = GuardSnapshot.capture(stage, "before", query,
                GuardContextHolder.getOrDefault(),
                nightmareBreakerProvider.getIfAvailable());

        // record snapshots to TraceStore for UI debugging
        try {
            TraceStore.append("orch.debug.snapshots", before.asMap());
        } catch (Throwable ignore) {
        }

        Throwable err = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            err = t;
            throw t;
        } finally {
            GuardSnapshot after = GuardSnapshot.capture(stage, "after", query,
                    GuardContextHolder.getOrDefault(),
                    nightmareBreakerProvider.getIfAvailable());

            try {
                TraceStore.append("orch.debug.snapshots", after.asMap());
            } catch (Throwable ignore) {
            }

            // only print when something meaningful happened to reduce spam
            if (shouldLog(before, after, err)) {
                log.info(after.prettyLine(err));
            } else if (isVerbose()) {
                log.debug(after.prettyLine(err));
            }
        }
    }

    private boolean shouldLog(GuardSnapshot before, GuardSnapshot after, Throwable err) {
        if (err != null) return true;

        // Guard mode flips
        if (before.bypass != after.bypass) return true;
        if (before.auxHardDown != after.auxHardDown) return true;
        if (before.auxDegraded != after.auxDegraded) return true;
        if (before.strike != after.strike) return true;
        if (before.compression != after.compression) return true;

        // Breaker transitions (open/half-open) in aux keys
        if (before.qtOpen != after.qtOpen) return true;
        if (before.disambigOpen != after.disambigOpen) return true;
        if (before.keywordOpen != after.keywordOpen) return true;
        if (before.fastOpen != after.fastOpen) return true;
        if (before.chatOpen != after.chatOpen) return true;

        // Orchestration decided BYPASS/STRIKE/COMPRESSION
        if (!before.modeLabel.equals(after.modeLabel)) return true;

        // Irregularity changed meaningfully
        return Math.abs(before.irregularity - after.irregularity) >= 0.10;
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.guard-trace.enabled", "false"));
    }

    private boolean isVerbose() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.guard-trace.verbose", "false"));
    }

    private String get(String key, String def) {
        if (env == null || key == null) {
            return def;
        }
        try {
            String v = env.getProperty(key);
            return (v == null) ? def : v;
        } catch (Exception ignore) {
            return def;
        }
    }

    private static String extractQuery(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }

        // common case: (String query, ...)
        if (args[0] instanceof String s) {
            return s;
        }

        // ChatWorkflow.continueChat(ChatRequestDto req, ...)
        Object a0 = args[0];
        if (a0 != null) {
            try {
                var m = a0.getClass().getMethod("getPrompt");
                Object v = m.invoke(a0);
                if (v instanceof String s) {
                    return s;
                }
            } catch (Exception ignore) {
            }
        }

        // fallback
        return String.valueOf(args[0]);
    }

    private static final class GuardSnapshot {
        final String stage;
        final String phase;
        final String queryClip;
        final boolean auxDegraded;
        final boolean auxHardDown;
        final boolean strike;
        final boolean compression;
        final boolean bypass;
        final double irregularity;

        final boolean qtOpen;
        final boolean disambigOpen;
        final boolean keywordOpen;
        final boolean fastOpen;
        final boolean chatOpen;
        final boolean webBraveOpen;
        final boolean webNaverOpen;

        final String auxDownLast; // TraceStore: aux.down.last
        final String auxBlockedLast; // TraceStore: aux.blocked.last

        final String modeLabel;
        final String orchReason;

        // Explain BYPASS triggers explicitly
        final boolean bypassByChatDown;
        final boolean bypassByWebBothDown;
        final boolean bypassBySilentFailure; // auxHardDown && irr>=0.25
        final boolean bypassByContextFlag;

        private GuardSnapshot(
                String stage,
                String phase,
                String queryClip,
                boolean auxDegraded,
                boolean auxHardDown,
                boolean strike,
                boolean compression,
                boolean bypass,
                double irregularity,
                boolean qtOpen,
                boolean disambigOpen,
                boolean keywordOpen,
                boolean fastOpen,
                boolean chatOpen,
                boolean webBraveOpen,
                boolean webNaverOpen,
                String auxDownLast,
                String auxBlockedLast,
                String modeLabel,
                String orchReason,
                boolean bypassByChatDown,
                boolean bypassByWebBothDown,
                boolean bypassBySilentFailure,
                boolean bypassByContextFlag
        ) {
            this.stage = stage;
            this.phase = phase;
            this.queryClip = queryClip;
            this.auxDegraded = auxDegraded;
            this.auxHardDown = auxHardDown;
            this.strike = strike;
            this.compression = compression;
            this.bypass = bypass;
            this.irregularity = irregularity;
            this.qtOpen = qtOpen;
            this.disambigOpen = disambigOpen;
            this.keywordOpen = keywordOpen;
            this.fastOpen = fastOpen;
            this.chatOpen = chatOpen;
            this.webBraveOpen = webBraveOpen;
            this.webNaverOpen = webNaverOpen;
            this.auxDownLast = auxDownLast;
            this.auxBlockedLast = auxBlockedLast;
            this.modeLabel = modeLabel;
            this.orchReason = orchReason;
            this.bypassByChatDown = bypassByChatDown;
            this.bypassByWebBothDown = bypassByWebBothDown;
            this.bypassBySilentFailure = bypassBySilentFailure;
            this.bypassByContextFlag = bypassByContextFlag;
        }

        static GuardSnapshot capture(String stage, String phase, String query, GuardContext ctx, NightmareBreaker nb) {
            String clip = clipQuery(query);

            boolean qtOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
            boolean disOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.DISAMBIGUATION_CLARIFY);
            boolean kwOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.KEYWORD_SELECTION_SELECT);
            boolean fastOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.FAST_LLM_COMPLETE);
            boolean chatOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.CHAT_DRAFT);

            boolean webBraveOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_BRAVE);
            boolean webNaverOpen = nb != null && nb.isOpenOrHalfOpen(NightmareKeys.WEBSEARCH_NAVER);

            boolean auxDegraded = ctx != null && ctx.isAuxDegraded();
            boolean auxHardDown = ctx != null && ctx.isAuxHardDown();
            boolean strike = ctx != null && ctx.isStrikeMode();
            boolean compression = ctx != null && ctx.isCompressionMode();
            boolean bypass = ctx != null && ctx.isBypassMode();
            double irr = ctx != null ? ctx.getIrregularityScore() : 0.0;

            OrchestrationSignals sig;
            try {
                sig = OrchestrationSignals.compute(query, nb, ctx);
            } catch (Exception e) {
                sig = null;
            }

            String orchReason = (sig != null)
                    ? safe(sig.reason())
                    : safe(ctx != null ? ctx.getBypassReason() : null);
            String modeLabel = (sig != null)
                    ? safe(sig.modeLabel())
                    : (bypass ? "BYPASS" : (strike ? "STRIKE" : (compression ? "COMPRESSION" : "NORMAL")));

            boolean bypassByChatDown = chatOpen; // OrchestrationSignals uses CHAT_DRAFT open
            boolean bypassByWebBothDown = webBraveOpen && webNaverOpen;
            boolean bypassBySilentFailure = auxHardDown && irr >= 0.25;
            boolean bypassByContext = bypass;

            String auxDownLast = safe(String.valueOf(TraceStore.get("aux.down.last")));
            if ("null".equals(auxDownLast)) auxDownLast = null;

            String auxBlockedLast = safe(String.valueOf(TraceStore.get("aux.blocked.last")));
            if ("null".equals(auxBlockedLast)) auxBlockedLast = null;

            return new GuardSnapshot(
                    stage, phase, clip,
                    auxDegraded, auxHardDown, strike, compression, bypass, irr,
                    qtOpen, disOpen, kwOpen, fastOpen, chatOpen, webBraveOpen, webNaverOpen,
                    auxDownLast, auxBlockedLast,
                    modeLabel, orchReason,
                    bypassByChatDown, bypassByWebBothDown, bypassBySilentFailure, bypassByContext
            );
        }

        Map<String, Object> asMap() {
            try {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("ts", Instant.now().toString());
                m.put("stage", stage);
                m.put("phase", phase);
                m.put("query", queryClip);
                m.put("ctx.auxDegraded", auxDegraded);
                m.put("ctx.auxHardDown", auxHardDown);
                m.put("ctx.strike", strike);
                m.put("ctx.compression", compression);
                m.put("ctx.bypass", bypass);
                m.put("ctx.irregularity", irregularity);
                m.put("nb.qtOpenOrHalf", qtOpen);
                m.put("nb.disambiguationOpenOrHalf", disambigOpen);
                m.put("nb.keywordOpenOrHalf", keywordOpen);
                m.put("nb.fastOpenOrHalf", fastOpen);
                m.put("nb.chatOpenOrHalf", chatOpen);
                m.put("nb.webBraveOpenOrHalf", webBraveOpen);
                m.put("nb.webNaverOpenOrHalf", webNaverOpen);
                m.put("orch.mode", modeLabel);
                m.put("orch.reason", orchReason);
                m.put("bypass.chatDown", bypassByChatDown);
                m.put("bypass.webBothDown", bypassByWebBothDown);
                m.put("bypass.silentFailure", bypassBySilentFailure);
                m.put("bypass.ctxFlag", bypassByContextFlag);
                if (auxDownLast != null) m.put("aux.down.last", auxDownLast);
                if (auxBlockedLast != null) m.put("aux.blocked.last", auxBlockedLast);
                return m;
            } catch (Throwable t) {
                return java.util.Collections.emptyMap();
            }
        }

        String prettyLine(Throwable err) {
            StringBuilder sb = new StringBuilder();
            sb.append("[GuardTrace] stage=").append(stage).append(" phase=").append(phase)
                    .append(" mode=").append(modeLabel)
                    .append(" bypass=").append(bypass)
                    .append(" strike=").append(strike)
                    .append(" compression=").append(compression)
                    .append(" auxHardDown=").append(auxHardDown)
                    .append(" auxDegraded=").append(auxDegraded)
                    .append(" irr=").append(String.format(Locale.ROOT, "%.2f", irregularity))
                    .append(" qtOpen=").append(qtOpen)
                    .append(" disambigOpen=").append(disambigOpen)
                    .append(" keywordOpen=").append(keywordOpen)
                    .append(" fastOpen=").append(fastOpen)
                    .append(" chatOpen=").append(chatOpen)
                    .append(" webBraveOpen=").append(webBraveOpen)
                    .append(" webNaverOpen=").append(webNaverOpen);

            if (bypass) {
                sb.append(" bypassTriggers={chatDown=").append(bypassByChatDown)
                        .append(", webBothDown=").append(bypassByWebBothDown)
                        .append(", silentFailure=").append(bypassBySilentFailure)
                        .append(", ctxFlag=").append(bypassByContextFlag)
                        .append('}');
            }

            sb.append(" reason=").append(orchReason);

            if (auxDownLast != null) {
                sb.append(" aux.down.last=").append(auxDownLast);
            }
            if (auxBlockedLast != null) {
                sb.append(" aux.blocked.last=").append(auxBlockedLast);
            }

            if (queryClip != null && !queryClip.isBlank()) {
                sb.append(" query=\"").append(queryClip).append("\"");
            }

            if (err != null) {
                sb.append(" err=").append(err.getClass().getSimpleName()).append(":").append(safe(err.getMessage()));
            }

            return sb.toString();
        }

        private static String clipQuery(String q) {
            if (q == null) return "";
            String t = q.replaceAll("[\r\n\t]+", " ").trim();
            int max = 140;
            if (t.length() > max) {
                return t.substring(0, max) + "...";
            }
            return t;
        }

        private static String safe(String s) {
            if (s == null) return "null";
            String t = s.replaceAll("[\r\n\t]+", " ").trim();
            if (t.length() > 240) {
                return t.substring(0, 240) + "...";
            }
            return t;
        }
    }
}
