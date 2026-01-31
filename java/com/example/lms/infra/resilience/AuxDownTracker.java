package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UAW: Unified aux-down marker.
 *
 * <p>
 * Centralizes setting GuardContext.auxDegraded/auxHardDown and leaving
 * breadcrumbs in TraceStore so downstream orchestration can make consistent
 * routing decisions within the same request.
 * </p>
 */
public final class AuxDownTracker {
    private AuxDownTracker() {
    }

    public static void markDegraded(String source, String reason) {
        mark(source, reason, false, null, null);
    }

    public static void markHardDown(String source, String reason) {
        mark(source, reason, true, null, null);
    }

    /**
     * Soft observation only:
     * - do NOT flip GuardContext auxDegraded/auxHardDown
     * - keep breadcrumbs for MoE/diagnostics only
     */
    public static void markSoft(String source, String reason) {
        String src = safe(source);
        String rsn = safe(reason);
        String evt = src + ":" + rsn;
        try {
            TraceStore.put("aux.soft.last", evt);
            TraceStore.putIfAbsent("aux.soft.first", evt);
            TraceStore.put("aux.soft." + src, rsn);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    /**
     * Soft observation with exception:
     * exception 정보를 포함하되 상태 플래그는 건드리지 않는 오버로딩
     */
    public static void markSoft(String source, String reason, Throwable err) {
        String rsn = (err != null) ? reason + " (" + err.getClass().getSimpleName() + ")" : reason;
        markSoft(source, rsn);
    }

    public static void markDegraded(String source, String reason, Throwable err) {
        mark(source, reason, false, null, err);
    }

    public static void markHardDown(String source, String reason, Throwable err) {
        mark(source, reason, true, null, err);
    }

    private static void mark(String source, String reason, boolean hard, String note, Throwable err) {
        String src = safe(source);
        String rsn = safe(reason);

        // 1) GuardContext flags
        GuardContext ctx = GuardContextHolder.get();
        if (ctx == null) {
            ctx = GuardContextHolder.getOrDefault();
        }
        if (ctx != null) {
            try {
                if (hard) {
                    ctx.setAuxHardDown(true);
                    ctx.setAuxDegraded(true); // hard implies degraded
                } else {
                    ctx.setAuxDegraded(true);
                }
                if (ctx.getBypassReason() == null || ctx.getBypassReason().isBlank()) {
                    ctx.setBypassReason(src + ":" + rsn);
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        // 2) TraceStore breadcrumbs
        try {
            TraceStore.put("aux.llm.down", true);
            TraceStore.put("aux.llm." + (hard ? "hardDown" : "degraded"), true);

            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("ts", Instant.now().toString());
            evt.put("source", src);
            evt.put("reason", rsn);
            evt.put("hard", hard);
            evt.put("thread", Thread.currentThread().getName());

            String pipeRoot = safe(MDC.get("pipe.root"));
            String pipeNode = safe(MDC.get("pipe.node"));
            if (!pipeRoot.isBlank()) {
                evt.put("pipe.root", pipeRoot);
            }
            if (!pipeNode.isBlank()) {
                evt.put("pipe.node", pipeNode);
            }

            if (note != null && !note.isBlank()) {
                evt.put("note", safe(note));
            }

            if (err != null) {
                evt.put("err", err.getClass().getSimpleName());
                String msg = err.getMessage();
                if (msg != null && !msg.isBlank()) {
                    evt.put("errMsg", safe(msg.length() > 100 ? msg.substring(0, 100) : msg));
                }
            }

            TraceStore.append("aux.down.events", evt);
            TraceStore.put("aux.down.last", evt);
            TraceStore.putIfAbsent("aux.down.first", evt);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
