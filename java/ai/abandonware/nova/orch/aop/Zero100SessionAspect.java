package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.LogCorrelation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

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
            gc = null;
        }

        boolean enabled = isZero100Enabled(gc, msg);
        if (!enabled) {
            return pjp.proceed();
        }

        long maxMinutes = (gc != null) ? gc.planLong("search.zero100.maxMinutes", props.getMaxMinutes()) : props.getMaxMinutes();
        long sliceMs = (gc != null) ? gc.planLong("search.zero100.sliceMs", props.getSliceMs()) : props.getSliceMs();
        long webTimeboxMs = (gc != null) ? gc.planLong("search.zero100.webTimeboxMs", props.getWebCallTimeboxMs()) : props.getWebCallTimeboxMs();
        long backoffHardCapMs = (gc != null) ? gc.planLong("search.zero100.backoffHardCapMs", props.getBackoffHardCapMs()) : props.getBackoffHardCapMs();

        String sid = "unknown";
        try {
            sid = LogCorrelation.sessionId();
        } catch (Throwable ignore) {
            sid = "unknown";
        }

        Zero100SessionRegistry.Slice slice = null;
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
            log.debug("[Zero100] registry.touch failed: {}", t.getMessage());
            slice = null;
        }

        try {
            TraceStore.put("zero100.enabled", true);
            TraceStore.put("zero100.sessionId", sid);
            if (slice != null) {
                TraceStore.put("zero100.mpIntent", slice.getMpIntent());
                TraceStore.put("zero100.slice.idx", slice.getSliceIndex());
                TraceStore.put("zero100.slice.ms", slice.getSliceMs());
                TraceStore.put("zero100.remainingMs", slice.remainingSessionMs());
                TraceStore.put("zero100.clampMode", String.valueOf(slice.getClampMode()));
                TraceStore.put("zero100.explorationRate", slice.getExplorationRate());
                TraceStore.put("zero100.web.timeboxMs", slice.getWebTimeboxMs());
                TraceStore.put("zero100.backoff.hardCapMs", slice.getBackoffHardCapMs());
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        return pjp.proceed();
    }

    private static boolean isZero100Enabled(GuardContext gc, String msg) {
        try {
            if (gc != null && gc.planBool("search.zero100.enabled", false)) {
                return true;
            }
        } catch (Throwable ignore) {
            // best-effort
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
            // best-effort
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

    private static String extractMessage(Object[] args) {
        if (args == null) return "";
        for (Object a : args) {
            if (a == null) continue;
            try {
                // ChatRequestDto
                if (a.getClass().getName().equals("com.example.lms.dto.ChatRequestDto")) {
                    java.lang.reflect.Method m = a.getClass().getMethod("getMessage");
                    Object v = m.invoke(a);
                    return (v == null) ? "" : String.valueOf(v);
                }
            } catch (Throwable ignore) {
                // best-effort
            }
            if (a instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
