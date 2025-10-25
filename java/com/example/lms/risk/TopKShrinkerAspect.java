
package com.example.lms.risk;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;



@Aspect
@Component
@ConditionalOnProperty(name="retrieval.topK.shrinkOnRisk", havingValue = "true", matchIfMissing = false)
public class TopKShrinkerAspect {

    @Autowired RiskScorer scorer;
    @Value("${retrieval.topK.base:24}") int baseK;
    @Value("${retrieval.topK.min:6}") int minK;

    @Around("execution(* *..AnalyzeWebSearchRetriever.*(..)) || execution(* *..WebSearchRetriever.*(..))")
    public Object adjust(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        // naive: if there is an integer arg interpreted as topK, adjust it
        for (int i=0;i<args.length;i++) {
            if (args[i] instanceof Integer) {
                int k = (Integer)args[i];
                int newK = Math.min(k, baseK);
                newK = scorer.shrinkTopK(newK, new RiskDecisionIndex(0.5), minK); // use mid risk if unavailable
                args[i] = newK;
                break;
            }
        }
        return pjp.proceed(args);
    }
}