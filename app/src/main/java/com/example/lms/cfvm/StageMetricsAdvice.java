package com.example.lms.cfvm;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;

@Aspect
@Component
@RequiredArgsConstructor
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.StageMetricsAdvice
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.StageMetricsAdvice
role: config
flags: [kg]
*/
public class StageMetricsAdvice {
    private final CfvmRawService cfvm;
    private static final double JB_SCALE_NANOS = 2_000_000_000d;
    @Around("execution(* com.example.lms.service.NaverSearchService.*(..))")
    public Object webStage(ProceedingJoinPoint pjp) throws Throwable { return aroundStage(pjp, RawSlot.Stage.WEB, "web"); }
    @Around("execution(* com.example.lms.service.VectorStoreService.*(..))")
    public Object vectorStage(ProceedingJoinPoint pjp) throws Throwable { return aroundStage(pjp, RawSlot.Stage.VECTOR, "vector"); }
    @Around("execution(* com.example.lms.service.rag.handler.KnowledgeGraphHandler.*(..))")
    public Object kgStage(ProceedingJoinPoint pjp) throws Throwable { return aroundStage(pjp, RawSlot.Stage.KG, "kg"); }
    private Object aroundStage(ProceedingJoinPoint pjp, RawSlot.Stage stage, String path) throws Throwable {
        long t0 = System.nanoTime();
        String sid = CfvmRawService.currentSessionIdOr("global");
        boolean failed = false;
        try { return pjp.proceed(); }
        catch (Throwable ex) { failed = true; throw ex; }
        finally {
            double jb = Math.min(1.0, (System.nanoTime() - t0) / JB_SCALE_NANOS);
            double cb = failed ? 1.0 : 0.0;
            String code = "JB" + bin(jb) + "_CB" + bin(cb);
            cfvm.push(RawSlot.builder()
                    .sessionId(sid)
                    .stage(stage)
                    .code(code)
                    .path(path)
                    .message("jb=" + jb + ", cb=" + cb)
                    .tags(Map.of("jb", String.valueOf(jb), "cb", String.valueOf(cb)))
                    .ts(Instant.now())
                    .build());
            double sim = ToyMatcher.updateAndScore(sid, stage, jb, cb);
            double threshold = 0.95;
            if (sim >= threshold) {
                cfvm.push(RawSlot.builder()
                        .sessionId(sid)
                        .stage(stage)
                        .code("Toy224Match")
                        .path("toy224")
                        .message("similarity=" + sim)
                        .tags(Map.of("sim", String.format("%.3f", sim)))
                        .ts(Instant.now())
                        .build());
            }
        }
    }
    private static int bin(double v) {
        if (v < 0.25) return 0; if (v < 0.50) return 1; if (v < 0.75) return 2; return 3;
    }
}