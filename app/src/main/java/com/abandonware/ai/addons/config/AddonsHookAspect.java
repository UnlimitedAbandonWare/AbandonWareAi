package com.abandonware.ai.addons.config;

import com.abandonware.ai.addons.onnx.OnnxSemaphoreGate;
import com.abandonware.ai.addons.cache.SingleFlightRegistry;
import com.abandonware.ai.addons.complexity.*;
import com.abandonware.ai.addons.config.AddonsProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.config.AddonsHookAspect
 * Role: config
 * Dependencies: com.abandonware.ai.addons.onnx.OnnxSemaphoreGate, com.abandonware.ai.addons.cache.SingleFlightRegistry, com.abandonware.ai.addons.complexity.*, +1 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.config.AddonsHookAspect
role: config
*/
public class AddonsHookAspect {

    private final OnnxSemaphoreGate onnxGate;
    private final SingleFlightRegistry singleFlight;
    private final ComplexityGatingCoordinator gating;
    private final AddonsProperties props;

    public AddonsHookAspect(OnnxSemaphoreGate onnxGate, SingleFlightRegistry singleFlight, ComplexityGatingCoordinator gating, AddonsProperties props) {
        this.onnxGate = onnxGate;
        this.singleFlight = singleFlight;
        this.gating = gating;
        this.props = props;
    }

    // 1) OnnxCrossEncoderReranker.rerank(..) 게이팅
    @Around("execution(* *..OnnxCrossEncoderReranker.rerank(..))")
    public Object aroundRerank(ProceedingJoinPoint pjp) throws Throwable {
        Supplier<Object> task = () -> {
            try { return pjp.proceed(); } catch (Throwable t) { throw new RuntimeException(t); }
        };
        Supplier<Object> fb = () -> {
            try { return pjp.getArgs()[0]; } catch (Exception e) { return null; }
        };
        try {
            return onnxGate.withPermit(() -> task.get(), () -> fb.get());
        } catch (RuntimeException ex) {
            return fb.get();
        }
    }

    // 2) AnalyzeWebSearchRetriever.fetch(String) 싱글플라이트
    @Around("execution(* *..AnalyzeWebSearchRetriever.fetch(..))")
    public Object aroundWebFetch(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String key = "web:" + (args != null && args.length>0 ? String.valueOf(args[0]) : "unknown");
        CompletableFuture<Object> cf = singleFlight.run(key, () -> CompletableFuture.supplyAsync(() -> {
            try { return pjp.proceed(); } catch (Throwable t) { throw new RuntimeException(t); }
        }), 3000);
        return cf.join();
    }

    // 3) RetrievalOrderService.decide*(..) 전후: 힌트 계산/반영(리플렉션)
    @Before("execution(* *..RetrievalOrderService.decide*(..))")
    public void beforeDecide(JoinPoint jp) {
        String q = extractQuery(jp.getArgs());
        var hints = gating.decide(q, Locale.KOREA, java.util.Map.of());
        HintsHolder.set(hints);
    }

    @AfterReturning(pointcut = "execution(* *..RetrievalOrderService.decide*(..))", returning = "ret")
    public void afterDecide(JoinPoint jp, Object ret) {
        try {
            var hints = HintsHolder.get();
            if (hints == null || ret == null) return;
            // Best-effort setter 호출
            trySet(ret, "setWebEnabled", new Class[]{boolean.class}, new Object[]{hints.enableWeb()});
            trySet(ret, "setWebTopK", new Class[]{int.class}, new Object[]{hints.webTopK()});
            trySet(ret, "setVectorTopK", new Class[]{int.class}, new Object[]{hints.vectorTopK()});
            trySet(ret, "setRoutingProfile", new Class[]{String.class}, new Object[]{hints.routingProfile()});
            trySet(ret, "setUseCrossEncoder", new Class[]{boolean.class}, new Object[]{hints.useCrossEncoder()});
            trySet(ret, "setEnableSecondPass", new Class[]{boolean.class}, new Object[]{hints.enable2Pass()});
        } finally {
            HintsHolder.clear();
        }
    }

    private static void trySet(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            var m = target.getClass().getMethod(method, types);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (Throwable ignore) {}
    }

    private static String extractQuery(Object[] args) {
        if (args == null) return "";
        for (Object a : args) {
            if (a == null) continue;
            // Common patterns: String, Query(text()), getText(), text()
            if (a instanceof String s) return s;
            try {
                var m = a.getClass().getMethod("text");
                Object v = m.invoke(a);
                if (v instanceof String s2) return s2;
            } catch (Throwable ignore) {}
            try {
                var m = a.getClass().getMethod("getText");
                Object v = m.invoke(a);
                if (v instanceof String s3) return s3;
            } catch (Throwable ignore) {}
        }
        return "";
    }
}