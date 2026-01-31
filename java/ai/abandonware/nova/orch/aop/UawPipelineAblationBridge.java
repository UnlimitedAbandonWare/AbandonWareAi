package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.AblationContributionTracker;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * DROP: UAW pipeline → ablation contribution bridge.
 *
 * <p>Turns coarse degrade signals into attribution penalties so that
 * post-mortem can identify which guard/pipeline step likely degraded output.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 260)
@Slf4j
public class UawPipelineAblationBridge {

    private final Environment env;

    public UawPipelineAblationBridge(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
                GuardContext gctx = null;
        try { gctx = GuardContextHolder.get(); } catch (Throwable ignore) {}
        boolean isUaw = gctx != null && gctx.planBool("uaw.autolearn", false);
        boolean degradeWeb = gctx != null && gctx.planBool("uaw.degradeWeb", false);
        boolean degradeRag = gctx != null && gctx.planBool("uaw.degradeRag", false);

        boolean dbgSearch = truthy(TraceStore.get("dbg.search.enabled"));
        boolean bridgeActive = isUaw || dbgSearch || truthy(TraceStore.get("uaw.ablation.bridge"));

        if (bridgeActive) {
            // best-effort breadcrumb
            try { TraceStore.put("uaw.ablation.bridge", true); } catch (Throwable ignore) {}
        }

        try {
            return pjp.proceed();
        } finally {
            if (bridgeActive) {
                // Enrich penalty breadcrumb messages with plan metadata when present.
                String planHint = "";
                try {
                    Object pid = TraceStore.get("uaw.pipeline.plan.id");
                    Object scen = TraceStore.get("uaw.pipeline.plan.scenario");
                    Object var = TraceStore.get("uaw.pipeline.plan.variant");
                    if (pid != null || scen != null || var != null) {
                        planHint = " (planId=" + pid + ",scenario=" + scen + ",variant=" + var + ")";
                    }
                } catch (Throwable ignore) {}

                if (degradeWeb) {
                    AblationContributionTracker.recordPenaltyOnce(
                            "uaw.degrade.web",
                            "uaw.pipeline",
                            "degrade:web",
                            0.08,
                            "websearch breaker-open; forced web off" + planHint);
                }
                if (degradeRag) {
                    AblationContributionTracker.recordPenaltyOnce(
                            "uaw.degrade.rag",
                            "uaw.pipeline",
                            "degrade:rag",
                            0.06,
                            "retrieval breaker-open; forced rag off" + planHint);
                }


                // keywordSelection blank fallback (silent quality degradation)
                try {
                    Object mode = TraceStore.get("keywordSelection.mode");
                    if ("fallback_blank".equals(String.valueOf(mode))) {
                        AblationContributionTracker.recordPenaltyOnce(
                                "uaw.kw.blankFallback",
                                "keyword_selection",
                                "blank_fallback",
                                0.05,
                                "keywordSelection blank fallback" + planHint);
                    }
                } catch (Throwable ignore) {
                }

                // aux-blocked: breaker/guard skipped auxiliary steps (nightmare/cb/open etc)
                try {
                    Object last = TraceStore.get("aux.blocked.last");
                    Object cnt = TraceStore.get("aux.blocked.count");
                    int n = asInt(cnt, 0);
                    if (last != null || n > 0) {
                        AblationContributionTracker.recordPenaltyOnce(
                                "uaw.aux.blocked",
                                "aux",
                                "blocked",
                                0.06,
                                "aux blocked" + (last != null ? " last=" + clip(String.valueOf(last), 120) : "") + " count=" + n + planHint);
                    }
                } catch (Throwable ignore) {
                }

                // QueryTransformer LLM missing (MODEL_REQUIRED) → forced bypass/heuristics
                try {
                    Object code = TraceStore.get("qtx.llm.error.code");
                    if (code != null && "MODEL_REQUIRED".equalsIgnoreCase(String.valueOf(code))) {
                        AblationContributionTracker.recordPenaltyOnce(
                                "uaw.qtx.modelRequired",
                                "query_transformer",
                                "llm_missing",
                                0.08,
                                "QueryTransformer missing model (MODEL_REQUIRED)" + planHint);
                    }
                } catch (Throwable ignore) {
                }

                // web.await skipped due to missing future wiring (async bug) → web result absent
                try {
                    Object reason = TraceStore.get("web.await.skipped.last");
                    if (reason != null && "missing_future".equalsIgnoreCase(String.valueOf(reason))) {
                        AblationContributionTracker.recordPenaltyOnce(
                                "uaw.web.missingFuture",
                                "web",
                                "missing_future",
                                0.08,
                                "web await skipped (missing_future)" + planHint);
                    }
                } catch (Throwable ignore) {
                }

                // starvation fallback is already emitted as faultmask:websearch:starvation,
                // but keep a cheap hint as well.
                if (truthy(TraceStore.get("web.failsoft.starvationFallback.used"))) {
                    AblationContributionTracker.recordPenaltyOnce(
                            "uaw.web.starvationFallback",
                            "uaw.web",
                            "starvationFallback",
                            0.06,
                            "web-failsoft starvation fallback used" + planHint);
                }

                // Hybrid starved: both engines ended up empty (often breaker-skip + hard-timeout).
                if (Boolean.TRUE.equals(TraceStore.get("web.hybrid.starved"))) {
                    Object reason = TraceStore.get("web.hybrid.starved.reason");
                    AblationContributionTracker.recordPenaltyOnce(
                            "uaw.web.hybridStarved",
                            "uaw.web",
                            "hybrid:starved",
                            0.10,
                            "hybrid merged=0" + (reason != null ? " reason=" + reason : "") + planHint);
                }

                // Failsoft domain misroute: strict-domain policy produced no results and hatch rerouted.
                if (Boolean.TRUE.equals(TraceStore.get("web.failsoft.domainMisroute.reported"))) {
                    Object host = TraceStore.get("web.failsoft.domainMisroute.host");
                    Object q = TraceStore.get("web.failsoft.domainMisroute.query");
                    AblationContributionTracker.recordPenaltyOnce(
                            "uaw.web.domainMisroute",
                            "uaw.web",
                            "domainMisroute",
                            0.04,
                            "domain misroute" + (host != null ? " host=" + host : "") + (q != null ? " query=" + q : "") + planHint);
                }
            }
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }

    private static int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static String clip(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }


}