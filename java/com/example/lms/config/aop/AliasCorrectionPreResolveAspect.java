package com.example.lms.config.aop;

import com.example.lms.service.correction.VectorAliasCorrector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;



@Slf4j
@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "correction.alias.enabled", havingValue = "true", matchIfMissing = false)
public class AliasCorrectionPreResolveAspect {

    private final VectorAliasCorrector corrector;

    /**
     * Before calling resolvers (e.g., ConceptResolver), correct string arguments.
     * Pointcut is intentionally broad but safe; it matches any method under the 'service' package
     * whose simple name contains 'Resolver' OR method name starts with 'resolve'.
     */
    @Around(
        "execution(* com.example.lms.service..*Resolver*.*(..)) || " +
        "execution(* com.example.lms.service..*.resolve*(..))"
    )
    public Object correctArgs(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return pjp.proceed();
        }
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String s && !s.isBlank()) {
                String orig = s;
                String repl = corrector.correct(s).orElse(s);
                if (!repl.equals(orig)) {
                    args[i] = repl;
                    changed = true;
                }
            }
        }
        if (changed) {
            log.debug("[alias-corrector] arguments updated before resolver: {}", (Object) args);
        }
        return pjp.proceed(args);
    }
}