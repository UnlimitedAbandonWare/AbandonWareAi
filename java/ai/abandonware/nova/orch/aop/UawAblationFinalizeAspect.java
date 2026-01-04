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
 * Finalize ablation attribution once per UAW run.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 250)
@Slf4j
public class UawAblationFinalizeAspect {

    private final Environment env;

    public UawAblationFinalizeAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
                GuardContext gctx = null;
        try { gctx = GuardContextHolder.get(); } catch (Throwable ignore) {}
        boolean isUaw = gctx != null && gctx.planBool("uaw.autolearn", false);

        try {
            return pjp.proceed();
        } finally {
            if (isUaw) {
                try {
                    AblationContributionTracker.finalizeTraceIfNeeded();
                    TraceStore.put("uaw.ablation.finalized", true);
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
