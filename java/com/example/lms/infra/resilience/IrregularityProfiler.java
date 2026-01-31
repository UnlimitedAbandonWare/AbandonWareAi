package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UAW 미구현 축: 오케스트레이션 중 비정상 신호를 GuardContext에 누적.
 * - 가드(looksWeak) 완화/우회 트리거
 * - Bypass Routing(증거 기반 답변) 트리거에 활용
 */
@Component
public class IrregularityProfiler {

    public void bump(GuardContext ctx, double delta, String reason) {
        if (ctx == null) return;
        ctx.bumpIrregularity(delta, reason);
        try {
            TraceStore.put("irregularity.score", ctx.getIrregularityScore());
            TraceStore.append("irregularity.events", Map.of(
                    "ts", System.currentTimeMillis(),
                    "delta", delta,
                    "score", ctx.getIrregularityScore(),
                    "reason", (reason == null ? "" : reason)
            ));
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("irregularity.last", reason);
            }
        } catch (Throwable ignore) {
            // trace는 best-effort
        }
    }

    public void markHighRisk(GuardContext ctx, String reason) {
        if (ctx == null) return;
        ctx.setHighRiskQuery(true);
        bump(ctx, 0.25, reason);
    }
}
