package com.example.lms.infra.cache;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import java.time.Duration;

/**
 * Aspect to apply single‑flight semantics to any method annotated with
 * {@link SingleFlight}.  The SpEL expression provided via
 * {@code keyExpr} is evaluated against the method arguments to produce
 * a string key.  All concurrent invocations with the same key share
 * a single execution and return the same result.  A Redis lock is used
 * to coordinate across processes.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class SingleFlightAspect {
    private final SingleFlightExecutor executor;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(sf)")
    public Object around(ProceedingJoinPoint pjp, SingleFlight sf) throws Throwable {
        // Build evaluation context for SpEL.  Variables are named a0, a1, etc.
        Object[] args = pjp.getArgs();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("a" + i, args[i]);
        }
        Expression expr = parser.parseExpression(sf.keyExpr());
        String key = expr.getValue(ctx, String.class);
        int ttl = sf.ttlSeconds() <= 0 ? 30 : sf.ttlSeconds();
        return executor.run(key, () -> {
            try {
                return pjp.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }, Duration.ofSeconds(ttl), new SingleFlightExecutor.Serializer<>() {
            @Override
            public byte[] serialize(Object v) {
                if (v == null) return new byte[0];
                return java.util.Base64.getEncoder().encode(String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            @Override
            public Object deserialize(byte[] bytes) {
                if (bytes == null || bytes.length == 0) return null;
                byte[] decoded = java.util.Base64.getDecoder().decode(bytes);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            }
        });
    }
}