
package com.example.lms.resilience;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.util.Arrays;




@Aspect
@Component
@ConditionalOnBean(SingleFlightManager.class)
@ConditionalOnProperty(name="cache.singleflight.enabled", havingValue = "true", matchIfMissing = false)
public class SingleFlightAspect {


private final org.springframework.core.env.Environment env;

@org.springframework.beans.factory.annotation.Autowired
public SingleFlightAspect(SingleFlightManager manager, org.springframework.core.env.Environment env) {
    this.manager = manager;
    this.env = env;
    long timeout = env.getProperty("cache.singleflight.timeout-ms", Long.class, 15000L);
    String strategy = env.getProperty("cache.singleflight.key-strategy", "METHOD_AND_ARGS");
    org.slf4j.LoggerFactory.getLogger(SingleFlightAspect.class)
        .info("[SingleFlight] aspect enabled (timeout={}ms, strategy={})", timeout, strategy);
}


    @Autowired
    private SingleFlightManager manager;

    @Around("execution(* *..NaverSearchService.*(..)) || execution(* *..WebSearchRetriever.*(..))")
    public Object dedupe(ProceedingJoinPoint pjp) throws Throwable {
        String key = pjp.getSignature().toShortString() + Arrays.deepToString(pjp.getArgs());
        return manager.run(key, () -> {
            try {
                return pjp.proceed();
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        });
    }
}