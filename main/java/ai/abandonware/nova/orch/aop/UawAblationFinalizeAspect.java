package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.AblationContributionTracker;
import com.example.lms.trace.SafeRedactor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;

    public UawAblationFinalizeAspect(Environment env,
                                     ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this.env = env;
        this.blackboxProvider = blackboxProvider;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
        GuardContext gctx = null;
        try {
            gctx = GuardContextHolder.get();
        } catch (Throwable ignore) {
            TraceStore.put("uaw.ablation.guardContext.skipped", true);
            TraceStore.put("uaw.ablation.guardContext.reason", "guard_context_unavailable");
        }
        boolean isUaw = gctx != null && gctx.planBool("uaw.autolearn", false);

        try {
            return pjp.proceed();
        } finally {
            boolean shouldFinalize = isUaw
                    || truthy(TraceStore.get("uaw.ablation.bridge"))
                    || truthy(TraceStore.get("dbg.search.enabled"));

            if (shouldFinalize) {
                try {
                    AblationContributionTracker.finalizeTraceIfNeeded();
                    TraceStore.put("uaw.ablation.finalized", true);
                    RagFailureBlackboxService blackbox = blackboxProvider == null
                            ? null
                            : blackboxProvider.getIfAvailable();
                    if (blackbox != null) {
                        blackbox.refresh("UawAblationFinalizeAspect");
                    }
                } catch (Throwable failure) {
                    traceFinalizeFailure(failure);
                    log.debug("[UawAblationFinalize] finalize failed errorType={} errorHash={} errorLength={}",
                            errorType(failure), SafeRedactor.hashValue(messageOf(failure)),
                            messageLength(failure));
                }
            }
        }
    }

    private static void traceFinalizeFailure(Throwable failure) {
        TraceStore.put("uaw.ablation.finalize.failed", true);
        TraceStore.put("uaw.ablation.finalize.errorType", errorType(failure));
        TraceStore.put("uaw.ablation.finalize.errorHash", SafeRedactor.hashValue(messageOf(failure)));
        TraceStore.put("uaw.ablation.finalize.errorLength", messageLength(failure));
    }

    private static String errorType(Throwable failure) {
        String type = failure == null ? "unknown" : failure.getClass().getSimpleName();
        return SafeRedactor.traceLabelOrFallback(type, "unknown");
    }

    private static String messageOf(Throwable failure) {
        return failure == null ? null : failure.getMessage();
    }

    private static int messageLength(Throwable failure) {
        String message = messageOf(failure);
        return message == null ? 0 : message.length();
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) && numeric != 0.0;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }


}
