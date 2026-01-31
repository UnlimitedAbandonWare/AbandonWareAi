package com.abandonware.patch.infra.cache;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

@Aspect
public class SingleFlightAspect {

    private final SingleFlightExecutor executor;
    private final ExpressionParser parser = new SpelExpressionParser();

    public SingleFlightAspect(SingleFlightExecutor executor) { this.executor = executor; }

    @Around("@annotation(singleFlight)")
    public Object around(ProceedingJoinPoint pjp, SingleFlight singleFlight) {
        String key = evalKey(pjp, singleFlight.key());
        return executor.run(key, () -> {
            try { return pjp.proceed(); } catch (Throwable t) { throw new RuntimeException(t); }
        });
    }

    private String evalKey(ProceedingJoinPoint pjp, String expr) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        for (int i = 0; i < names.length; i++) ctx.setVariable(names[i], args[i]);
        Expression e = parser.parseExpression(expr);
        Object v = e.getValue(ctx);
        return String.valueOf(v);
    }
}