package ai.abandonware.nova.orch.aop;

import com.example.lms.service.NaverSearchService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Interrupt hygiene for pooled web-search threads.
 *
 * <p>EROR_AWS 로그에서 Naver korean-trace search가 InterruptedException으로 실패하는 케이스가 반복적으로 관측됨.
 * timeout/cancel이 발생하면 executor thread의 interrupt flag가 남아 이후 작업까지 오염시키는 경우가 있어,
 *
 * <ul>
 *   <li>메서드 진입 시 interrupt 상태면 빠르게 빈 결과로 반환</li>
 *   <li>InterruptedException 계열 예외면 interrupt flag를 clear 한 뒤 빈 결과로 반환 (fail-soft)</li>
 * </ul>
 *
 * <p>주의: 오직 interrupt 계열만 흡수하며, 그 외 예외는 그대로 throw.
 */
@Aspect
public class NaverInterruptHygieneAspect {

    private static final Logger log = LoggerFactory.getLogger(NaverInterruptHygieneAspect.class);

    @Around("execution(com.example.lms.service.NaverSearchService.SearchResult com.example.lms.service.NaverSearchService.searchWithTraceSync(..))")
    public Object aroundSearchWithTraceSync(ProceedingJoinPoint pjp) throws Throwable {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted(); // clear
            return emptyTraceResult();
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            if (looksInterrupted(t)) {
                Thread.interrupted(); // clear
                log.debug("[InterruptHygiene] swallowed interrupted searchWithTraceSync()");
                return emptyTraceResult();
            }
            throw t;
        }
    }

    @Around("execution(java.util.List com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundSearchSnippetsSync(ProceedingJoinPoint pjp) throws Throwable {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted(); // clear
            return Collections.emptyList();
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            if (looksInterrupted(t)) {
                Thread.interrupted(); // clear
                log.debug("[InterruptHygiene] swallowed interrupted searchSnippetsSync()");
                return Collections.emptyList();
            }
            throw t;
        }
    }

    private NaverSearchService.SearchResult emptyTraceResult() {
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private boolean looksInterrupted(Throwable t) {
        if (t == null) return false;
        if (t instanceof InterruptedException) return true;
        String name = t.getClass().getName();
        if (name != null && name.contains("Interrupted")) return true;
        return looksInterrupted(t.getCause());
    }
}
