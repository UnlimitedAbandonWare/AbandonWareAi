package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * DROP: Naver Search domainProfile hatch.
 *
 * <p>Some call-sites pass domainProfile="GENERAL" intending "no restriction". However,
 * NaverSearchService may interpret it as a strict/unknown profile and over-filter results.
 * This aspect temporarily converts GENERAL -> null when not in officialOnly mode.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class NaverDomainProfileHatchAspect {

    @Around("execution(* com.example.lms.service.NaverSearchService.searchWithTraceSync(..))"
            + " || execution(* com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        GuardContext ctx;
        try {
            ctx = GuardContextHolder.get();
        } catch (Throwable ignore) {
            ctx = null;
        }

        if (ctx == null) {
            return pjp.proceed();
        }

        String original = ctx.getDomainProfile();
        boolean touched = false;
        try {
            if (original != null && original.equalsIgnoreCase("GENERAL") && !ctx.isOfficialOnly()) {
                ctx.setDomainProfile(null);
                touched = true;
                TraceStore.put("naver.domainProfile.hatch", "GENERAL->null");
            }
            return pjp.proceed();
        } finally {
            if (touched) {
                try {
                    ctx.setDomainProfile(original);
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
