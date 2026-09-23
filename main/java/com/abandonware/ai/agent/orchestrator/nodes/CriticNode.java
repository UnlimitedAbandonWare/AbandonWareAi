package com.abandonware.ai.agent.orchestrator.nodes;

import com.abandonware.ai.agent.orchestrator.recovery.FailureClass;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;



/** Minimal pure critic. It inspects state only and performs no external IO. */
@Component
public final class CriticNode {
    private final RecoveryPolicy policy;

    public CriticNode() {
        this(RecoveryPolicy.load());
    }

    @Autowired
    public CriticNode(RecoveryPolicy policy) {
        this.policy = policy == null ? RecoveryPolicy.load() : policy;
    }

    public Map<String,Object> run(Map<String,Object> ctx){
        Map<String,Object> out = new HashMap<>();
        Map<String,Object> signals = new java.util.LinkedHashMap<>();
        Object rag = ctx != null ? ctx.get("rag.retrieve") : null;
        Object web = ctx != null ? ctx.get("web.search") : null;
        boolean hasEvidence = (rag != null) || (web != null);
        int missing = 0;
        if (!hasEvidence) {
            missing++;
            signals.put("evidence", "absent");
        }

        int minCitations = policy.minCitations();
        int citations = countCitations(ctx, minCitations);
        if (citations < minCitations) {
            missing++;
            signals.put("citations", citations);
        }

        if (Boolean.TRUE.equals(ctx != null ? ctx.get("schema.violation") : null)) {
            missing++;
            signals.put("schema", "violation");
        }

        TraceContext traceContext = TraceContext.current();
        boolean traceContextPresent = TraceContext.isAttached();
        long remain = traceContext.remainingMillis();
        int budgetSoftMs = policy.budgetSoftMs();
        if (remain < budgetSoftMs) {
            missing++;
            signals.put("budget_ms_left", remain);
        }

        if (Boolean.TRUE.equals(ctx != null ? ctx.get("policy.denied") : null)) {
            missing++;
            signals.put("policy", "denied");
        }

        Verdict verdict;
        if (missing == 0) {
            verdict = Verdict.accept(0.85, "criteria passed");
        } else if ("denied".equals(signals.get("policy"))) {
            verdict = new Verdict(Verdict.Decision.ABORT, FailureClass.POLICY, 0.95, "policy denied", signals);
        } else if (remain < budgetSoftMs) {
            verdict = new Verdict(Verdict.Decision.RECOVER, FailureClass.BUDGET, 0.8, "budget nearly exhausted", signals);
        } else if (citations < minCitations) {
            verdict = new Verdict(Verdict.Decision.RECOVER, FailureClass.DATA, 0.75, "insufficient evidence", signals);
        } else {
            verdict = new Verdict(Verdict.Decision.RECOVER, FailureClass.UNKNOWN, 0.5, "unknown recovery trigger", signals);
        }

        out.put("verdict", verdict);
        out.put("critic", verdict.decision().name().toLowerCase(java.util.Locale.ROOT));
        traceMoeCritic(verdict, traceContextPresent);
        return out;
    }

    private static void traceMoeCritic(Verdict verdict, boolean traceContextPresent) {
        if (verdict == null) {
            return;
        }
        boolean exhausted = verdict.decision() == Verdict.Decision.ABORT
                || verdict.decision() == Verdict.Decision.RECOVER;
        Object previous = TraceStore.get("moe.criticAttempts");
        int attempts = previous instanceof Number n ? Math.max(0, n.intValue()) + 1 : 1;
        TraceStore.put("moe.criticAttempts", attempts);
        TraceStore.put("moe.criticExhausted", exhausted);
        TraceStore.put("moe.criticLastReason", criticReason(verdict));
        TraceStore.put("critic.traceContextPresent", traceContextPresent);
        TraceStore.put("critic.exhausted", exhausted);
        TraceStore.put("critic.exhaustedReason", exhausted ? criticReason(verdict) : "");
    }

    private static String criticReason(Verdict verdict) {
        String raw = verdict.reason();
        String code = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return SafeRedactor.traceLabelOrFallback(code, "unknown");
    }

    private int countCitations(Map<String,Object> ctx, int limit) {
        if (ctx == null || limit <= 0) return 0;
        int count = 0;
        count += countFrom(ctx.get("citations"), limit - count);
        if (count >= limit) return count;
        count += countFrom(ctx.get("rag.retrieve"), limit - count);
        if (count >= limit) return count;
        count += countFrom(ctx.get("web.search"), limit - count);
        return count;
    }

    private int countFrom(Object value, int limit) {
        if (value == null || limit <= 0) return 0;
        if (value instanceof Collection<?> c) return Math.min(c.size(), limit);
        if (value instanceof Map<?,?> m) {
            Object citations = m.get("citations");
            if (citations instanceof Collection<?> c) return Math.min(c.size(), limit);
            Object results = m.get("results");
            if (results instanceof Collection<?> c) return Math.min(c.size(), limit);
            return m.isEmpty() ? 0 : 1;
        }
        if (value instanceof CharSequence s) return s.isEmpty() ? 0 : 1;
        return 1;
    }
}
