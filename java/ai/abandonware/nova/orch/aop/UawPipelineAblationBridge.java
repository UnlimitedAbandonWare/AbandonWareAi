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

        if (isUaw) {
            // best-effort breadcrumb
            try { TraceStore.put("uaw.ablation.bridge", true); } catch (Throwable ignore) {}
        }

        try {
            return pjp.proceed();
        } finally {
            if (isUaw) {
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
                // starvation fallback is already emitted as faultmask:websearch:starvation,
                // but keep a cheap hint as well.
                if (TraceStore.get("web.failsoft.starvationFallback.used") != null) {
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
}
