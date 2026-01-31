package ai.abandonware.nova.orch.failpattern.aop;

import ai.abandonware.nova.orch.failpattern.PolicyAdjuster;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.List;

/**
 * RetrievalOrderService 결과를 "약하게" 조정하는 AOP.
 *
 * <p>코어 로직(서비스 구현) 수정 없이, plan을 reorder/skip해서
 * 실패 소스의 cooldown을 반영한다.
 */
@Aspect
public class RetrievalOrderFeedbackAspect {

    private final PolicyAdjuster adjuster;

    public RetrievalOrderFeedbackAspect(PolicyAdjuster adjuster) {
        this.adjuster = adjuster;
    }

    @Around("execution(java.util.List *..RetrievalOrderService.decideOrder(..))")
    public Object aroundDecideOrder(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof List<?> list) || list.isEmpty()) {
            return ret;
        }
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) list;
        return adjuster.adjustOrder(raw);
    }
}
