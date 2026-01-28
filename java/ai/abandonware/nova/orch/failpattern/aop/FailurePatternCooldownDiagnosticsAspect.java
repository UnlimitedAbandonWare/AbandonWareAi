package ai.abandonware.nova.orch.failpattern.aop;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Request-scoped diagnostics for failure-pattern cooldown state.
 *
 * <p>
 * Goal: make risk changes (ex: LLM CIRCUIT_OPEN cooldown shortened) observable
 * without leaking
 * any trace into the end-user answer.
 */
@Aspect
public class FailurePatternCooldownDiagnosticsAspect {

    private final FailurePatternOrchestrator failurePatterns;
    private final NovaFailurePatternProperties props;

    public FailurePatternCooldownDiagnosticsAspect(
            FailurePatternOrchestrator failurePatterns,
            NovaFailurePatternProperties props) {
        this.failurePatterns = failurePatterns;
        this.props = props;
    }

    @Around("execution(* com.example.lms.transform.QueryTransformer.transformEnhanced*(..))" +
            " || execution(* com.example.lms.transform.QueryTransformer.transform(..))")
    public Object captureLlmCooldown(ProceedingJoinPoint pjp) throws Throwable {
        recordIfAbsent("llm");
        return pjp.proceed();
    }

    @Around("execution(* com.example.lms.service.disambiguation.QueryDisambiguationService.clarify(..))")
    public Object captureDisambigCooldown(ProceedingJoinPoint pjp) throws Throwable {
        recordIfAbsent("disambig");
        return pjp.proceed();
    }

    private void recordIfAbsent(String source) {
        if (failurePatterns == null || source == null)
            return;

        String base = "failpattern.cooldown." + source + ".";
        try {
            Object existing = TraceStore.get(base + "remainingMs");
            if (existing != null)
                return;

            FailurePatternOrchestrator.CooldownView v = failurePatterns.inspectCooldown(source);

            TraceStore.put(base + "coolingDown", v.coolingDown());
            TraceStore.put(base + "remainingMs", v.remainingMs());
            TraceStore.put(base + "lastCooldownMs", v.lastCooldownMs());
            TraceStore.put(base + "lastPolicy", v.lastCooldownPolicy());
            TraceStore.put(base + "lastKind", v.lastKind());
            TraceStore.put(base + "circuitOpenStrikes", v.circuitOpenStrikes());
            TraceStore.put(base + "circuitOpenEscalated", v.circuitOpenEscalated());

            // Config breadcrumbs (once/request)
            if (TraceStore.get("failpattern.cooldown.config.llmCircuitOpenBaseMs") == null) {
                TraceStore.put("failpattern.cooldown.config.circuitOpenDefaultMs",
                        props.getFeedback().getCircuitOpenCooldownSeconds() * 1000L);
                TraceStore.put("failpattern.cooldown.config.llmCircuitOpenBaseMs",
                        props.getFeedback().getLlmCircuitOpenCooldownSeconds() * 1000L);
                TraceStore.put("failpattern.cooldown.config.llmAdaptive.enabled",
                        props.getFeedback().isLlmCircuitOpenAdaptiveEnabled());
                TraceStore.put("failpattern.cooldown.config.llmAdaptive.windowSeconds",
                        props.getFeedback().getLlmCircuitOpenAdaptiveWindowSeconds());
                TraceStore.put("failpattern.cooldown.config.llmAdaptive.strikeThreshold",
                        props.getFeedback().getLlmCircuitOpenAdaptiveStrikeThreshold());
            }
        } catch (Throwable ignored) {
            // best-effort diagnostics
        }
    }
}
