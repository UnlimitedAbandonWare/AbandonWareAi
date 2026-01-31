// src/main/java/com/example/lms/trace/OrchestrationHotspotAspect.java
package com.example.lms.trace;

import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.routing.RouterPolicy;
import com.example.lms.ai.moe.MoeCandidateRouter;
import com.example.lms.service.verification.EvidenceGate;
import com.example.lms.service.verification.EvidenceSnapshot;
import com.example.lms.service.disambiguation.DisambiguationResult;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;






@Aspect
@Component
@ConditionalOnProperty(name = "lms.trace.hotspot.enabled", havingValue = "true", matchIfMissing = true)
public class OrchestrationHotspotAspect {

    // ────────────────────────────────────────────────────────────────────
    // ModelRouter.route(..) - 라우팅 의사결정 계측
    // ────────────────────────────────────────────────────────────────────
    @Around("execution(* com.example.lms.service.routing.ModelRouter.route(..))")
    public Object aroundModelRoute(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object ret = null;
        Throwable err = null;
        try {
            ret = pjp.proceed();
            return ret;
        } catch (Throwable ex) {
            err = ex;
            throw ex;
        } finally {
            long t1 = System.nanoTime();
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("method", "ModelRouter.route(..)");
            kv.put("latency_ms", (t1 - t0) / 1_000_000.0);
            // 입력 시그널 수집(가능한 경우)
            RouteSignal sig = extractRouteSignal(pjp.getArgs());
            if (sig != null) {
                kv.put("signal", sig.toSignalMap());
                Map<String, Object> hotspot = HotspotHeuristics.fromRouteSignal(sig);
                kv.put("hotspot", hotspot);
                kv.put("tokenBucket", HotspotHeuristics.tokenBucket(sig.maxTokens()));
            }
            if (ret instanceof ChatModel cm) {
                try {
                    ModelRouter target = (ModelRouter) pjp.getTarget();
                    String modelName = target.resolveModelName(cm);
                    kv.put("selected_model", modelName);
                } catch (Throwable ignore) {}
            }
            if (err != null) {
                kv.put("error", err.getClass().getSimpleName());
            }
            TraceLogger.emit("orchestration_decision", "route", kv);
            CloudPointerClient.trySend("orchestration_decision", "route", kv);
        }
    }

    private RouteSignal extractRouteSignal(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof RouteSignal s) return s;
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────
    // RouterPolicy.shouldPromote(RouteSignal) - 게이트 평가
    // ────────────────────────────────────────────────────────────────────
    @Around("execution(* com.example.lms.service.routing.RouterPolicy.shouldPromote(..))")
    public Object aroundShouldPromote(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object out = null;
        Throwable err = null;
        try {
            out = pjp.proceed();
            return out;
        } catch (Throwable ex) {
            err = ex;
            throw ex;
        } finally {
            long t1 = System.nanoTime();
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("method", "RouterPolicy.shouldPromote(..)");
            kv.put("latency_ms", (t1 - t0) / 1_000_000.0);
            RouteSignal sig = extractRouteSignal(pjp.getArgs());
            if (sig != null) {
                kv.put("signal", sig.toSignalMap());
                Map<String, Object> hotspot = HotspotHeuristics.fromRouteSignal(sig);
                kv.put("hotspot", hotspot);
                kv.put("tokenBucket", HotspotHeuristics.tokenBucket(sig.maxTokens()));
            }
            if (out instanceof Boolean b) {
                kv.put("allow", b);
            }
            if (err != null) {
                kv.put("error", err.getClass().getSimpleName());
            }
            TraceLogger.emit("orchestration_gate", "route", kv);
            CloudPointerClient.trySend("orchestration_gate", "route", kv);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // MoeCandidateRouter.selectModel(..) - 후보군 분포 측정
    // ────────────────────────────────────────────────────────────────────
    @Around("execution(* com.example.lms.ai.moe.MoeCandidateRouter.selectModel(..))")
    public Object aroundMoeSelect(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object out = null; Throwable err = null;
        List<String> candidates = null;
        try {
            Object[] args = pjp.getArgs();
            // candidates는 마지막 인자(List<String>)로 전달됨
            if (args != null && args.length > 0) {
                Object last = args[args.length - 1];
                if (last instanceof List<?> lst) {
                    candidates = lst.stream().map(String::valueOf).toList();
                }
            }
            out = pjp.proceed();
            return out;
        } catch (Throwable ex) {
            err = ex; throw ex;
        } finally {
            long t1 = System.nanoTime();
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("method", "MoeCandidateRouter.selectModel(..)");
            kv.put("latency_ms", (t1 - t0) / 1_000_000.0);
            if (candidates != null) kv.put("candidates", candidates);
            String sel = (out != null) ? out.toString() : null;
            kv.put("selected_id", sel);
            Map<String, Object> hotspot = HotspotHeuristics.fromCandidates(candidates, sel);
            kv.put("hotspot", hotspot);
            if (err != null) kv.put("error", err.getClass().getSimpleName());
            TraceLogger.emit("orchestration_moe_select", "route", kv);
            CloudPointerClient.trySend("orchestration_moe_select", "route", kv);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // EvidenceGate.allowGeneration(..) - 생성 허용/차단
    // ────────────────────────────────────────────────────────────────────
    @Around("execution(* com.example.lms.service.verification.EvidenceGate.allowGeneration(..)) || " +
            "execution(* com.example.lms.service.rag.guard.EvidenceGate.allowGeneration(..))")
    public Object aroundEvidenceGate(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object out = null; Throwable err = null;
        DisambiguationResult dr = null; EvidenceSnapshot ev = null;
        try {
            Object[] args = pjp.getArgs();
            if (args != null && args.length >= 2) {
                if (args[0] instanceof DisambiguationResult d) dr = d;
                if (args[1] instanceof EvidenceSnapshot e) ev = e;
            }
            out = pjp.proceed();
            return out;
        } catch (Throwable ex) {
            err = ex; throw ex;
        } finally {
            long t1 = System.nanoTime();
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("method", "EvidenceGate.allowGeneration(..)");
            kv.put("latency_ms", (t1 - t0) / 1_000_000.0);
            Map<String, Object> hotspot = HotspotHeuristics.fromEvidence(dr, ev);
            kv.put("hotspot", hotspot);
            if (out instanceof Boolean b) kv.put("allow", b);
            if (err != null) kv.put("error", err.getClass().getSimpleName());
            TraceLogger.emit("orchestration_evidence_gate", "verify", kv);
            CloudPointerClient.trySend("orchestration_evidence_gate", "verify", kv);
        }
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}