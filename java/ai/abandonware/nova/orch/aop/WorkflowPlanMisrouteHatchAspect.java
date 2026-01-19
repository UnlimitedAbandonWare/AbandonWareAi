package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * DROP: Workflow plan misroute hatch.
 *
 * <p>WorkflowOrchestrator.ensurePlanSelected() routes to ap1_auth_web.v1 when it thinks the request is
 * "legal/official". The token "공식" alone is too weak and often misroutes tech queries (e.g. "Gemini API 공식 문서"),
 * which then triggers official-only starvation. This hatch reverts ap1_auth_web.v1 -> safe plan when the only
 * "legal" cue is (공식|official) and there are no other strong legal/offical-policy cues.</p>
 */
@Aspect
public class WorkflowPlanMisrouteHatchAspect {

    private static final Pattern OTHER_LEGAL_CUES = Pattern.compile(
            "(?is)(개인정보|약관|정책|법령|규정|compliance|규제|법률|판례|소송|변호사|고시|시행령|시행규칙|행정|공공|정부)"
    );

    private final Environment env;

    public WorkflowPlanMisrouteHatchAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(String com.example.lms.orchestration.WorkflowOrchestrator.ensurePlanSelected(..))")
    public Object aroundEnsurePlanSelected(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof String planId)) {
            return ret;
        }

        if (planId == null) {
            return ret;
        }

        // Only care about ap1_auth_web.* plans
        if (!planId.startsWith("ap1_auth_web")) {
            return ret;
        }

        Object[] args = pjp.getArgs();
        GuardContext ctx = null;
        String userQuery = null;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof GuardContext gc) {
                    ctx = gc;
                } else if (a instanceof String s) {
                    // ensurePlanSelected signature has only one String arg = userQuery
                    userQuery = s;
                }
            }
        }

        String q = (userQuery == null) ? "" : userQuery;
        String lower = q.toLowerCase(Locale.ROOT);

        boolean hasOfficialToken = lower.contains("공식") || lower.contains("official");
        boolean hasOtherLegalCue = OTHER_LEGAL_CUES.matcher(q).find();

        if (hasOfficialToken && !hasOtherLegalCue) {
            String safePlanId = env.getProperty("plans.auto-select.safe", "safe_autorun.v1");

            if (ctx != null) {
                try {
                    ctx.setPlanId(safePlanId);
                } catch (Throwable ignore) {
                }
            }

            TraceStore.put("plan.hatch.ap1Misroute", true);
            TraceStore.put("plan.hatch.ap1Misroute.original", planId);
            TraceStore.put("plan.hatch.ap1Misroute.safe", safePlanId);
            TraceStore.put("plan.hatch.ap1Misroute.query", truncate(q, 160));

            return safePlanId;
        }

        return ret;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        if (x.length() <= max) return x;
        return x.substring(0, Math.max(0, max - 3)) + "...";
    }
}
