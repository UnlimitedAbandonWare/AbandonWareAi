package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UAW: "Fault Masking Layer" 감지용.
 * 예외를 삼킨(swallowed) 지점을 기록(unmask)하여 시스템이 "겉보기 정상"으로 좀비화되는 것을 방지.
 */
@Component
public class FaultMaskingLayerMonitor {

    private static final Logger log = LoggerFactory.getLogger(FaultMaskingLayerMonitor.class);

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    public void record(String stage, Throwable t, String note) {
        record(stage, t, null, note);
    }

    public void record(String stage, Throwable t, String context, String note) {
        String stg = (stage == null || stage.isBlank()) ? "unknown" : stage.trim();
        long n = counters.computeIfAbsent(stg, k -> new AtomicLong()).incrementAndGet();
        String last = summarize(t);

        try {
            TraceStore.put("faultmask.stage", stg);
            TraceStore.put("faultmask.count", n);
            if (!last.isBlank()) TraceStore.put("faultmask.last", last);
            if (note != null && !note.isBlank()) TraceStore.put("faultmask.note", note);
            if (context != null && !context.isBlank()) TraceStore.put("faultmask.context", safeTrim(context, 240));
            TraceStore.append("faultmask.events", stg + "#" + n + " " + last);
        } catch (Throwable ignore) {
            // tracing is best-effort
        }

        GuardContext ctx = GuardContextHolder.get();
        if (ctx != null) {
            double delta = 0.12;
            String reason = "faultmask:" + stg;
            if (irregularityProfiler != null) {
                irregularityProfiler.bump(ctx, delta, reason);
            } else {
                ctx.bumpIrregularity(delta, reason);
            }
        }

        // Log spam 방지: 첫 발생 + 20회마다만 WARN
        if (n == 1 || n % 20 == 0) {
            log.warn("[FaultMask] stage={} count={} last={} note={}", stg, n, last, note);
        } else {
            log.debug("[FaultMask] stage={} count={} last={} note={}", stg, n, last, note);
        }
    }

    private static String summarize(Throwable t) {
        if (t == null) return "";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        if (msg == null) msg = "";
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        msg = safeTrim(msg, 180);
        String name = root.getClass().getSimpleName();
        return msg.isBlank() ? name : (name + ": " + msg);
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max) + "…";
    }
}
