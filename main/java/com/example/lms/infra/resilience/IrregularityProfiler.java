package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IrregularityProfiler {

    public void bump(GuardContext ctx, double delta, String reason) {
        if (ctx == null) return;
        ctx.bumpIrregularity(delta, reason);
        try {
            String safeReason = SafeRedactor.traceLabelOrFallback(reason, "");
            TraceStore.put("irregularity.score", ctx.getIrregularityScore());
            TraceStore.append("irregularity.events", Map.of(
                    "ts", System.currentTimeMillis(),
                    "delta", delta,
                    "score", ctx.getIrregularityScore(),
                    "reason", safeReason
            ));
            if (!safeReason.isBlank()) {
                TraceStore.put("irregularity.last", safeReason);
            }
        } catch (Throwable ignore) {
            traceSuppressed("irregularity.trace", ignore);
        }
    }

    public void markHighRisk(GuardContext ctx, String reason) {
        if (ctx == null) return;
        ctx.setHighRiskQuery(true);
        bump(ctx, 0.25, reason);
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("irregularity.suppressed." + safeStage, true);
        TraceStore.put("irregularity.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
