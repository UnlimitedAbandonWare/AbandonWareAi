package com.example.lms.infra.resilience;

import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
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


    // MERGE_HOOK:PROJ_AGENT::FAULTMASK_STAGE_POLICY
    @Autowired(required = false)
    private StagePolicyProperties stagePolicy;

    @Autowired(required = false)
    private DebugEventStore debugEvents;

    public void record(String stage, Throwable t, String note) {
        record(stage, t, null, note);
    }

    public void record(String stage, Throwable t, String context, String note) {
        String stg = (stage == null || stage.isBlank()) ? "unknown" : stage.trim();
        String safeStg = safeLabel(stg, "unknown");
        long n = counters.computeIfAbsent(stg, k -> new AtomicLong()).incrementAndGet();
        String last = summarize(t);

        try {
            TraceStore.put("faultmask.stage", safeStg);
            TraceStore.put("faultmask.count", n);
            if (!last.isBlank()) TraceStore.put("faultmask.last", last);
            if (note != null && !note.isBlank()) TraceStore.put("faultmask.note", safeMessage(note, 180));
            if (context != null && !context.isBlank()) TraceStore.put("faultmask.context", safeContext(context, 240));
            TraceStore.append("faultmask.events", safeStg + "#" + n + " " + last);
        } catch (Throwable traceError) {
            traceSuppressed("faultMask.recordTrace", traceError);
        }

        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.get();
        } catch (Throwable tGet) {
            traceSuppressed("faultMask.guardContext", tGet);
            // Fail-soft: guard context might be absent from runtime classpath
            if (debugEvents != null) {
                try {
                    debugEvents.emit(
                            DebugProbeType.GUARD_CONTEXT,
                            DebugEventLevel.ERROR,
                            "guardContext.holder.missing",
                            "GuardContextHolder.get() failed inside FaultMaskingLayerMonitor (fail-soft)",
                            "FaultMaskingLayerMonitor",
                            java.util.Map.of(
                                    "stage", safeStg,
                                    "note", safeMessage(note, 180),
                                    "count", n
                            ),
                            tGet
                    );
                } catch (RuntimeException failure) {
                    traceSuppressed("faultMask.guardContextEmit", failure);
                    recordDebugEventFailure(failure);
                }
            }
        }
        if (ctx != null) {
            double delta = 0.12;
            String reason = "faultmask:" + safeStg;
            try {
                if (stagePolicy != null && stagePolicy.isEnabled()) {
                    StagePolicyProperties.Importance imp =
                            stagePolicy.importanceOf(stg, StagePolicyProperties.Importance.OPTIONAL);
                    delta = stagePolicy.irregularityDeltaFor(stg, imp);
                    TraceStore.put("faultMask.importance", imp.name());
                        TraceStore.put("faultMask.delta", delta);
                }
            } catch (Throwable policyError) {
                traceSuppressed("faultMask.stagePolicy", policyError);
            }
            if (irregularityProfiler != null) {
                irregularityProfiler.bump(ctx, delta, reason);
            } else {
                ctx.bumpIrregularity(delta, reason);
            }
        }

        // Structured event for web diagnostics + console JSON.
        if (debugEvents != null) {
            String errType = (t == null) ? "none" : t.getClass().getSimpleName();
            try {
                debugEvents.emit(
                        DebugProbeType.FAULT_MASK,
                        DebugEventLevel.WARN,
                        "faultMask." + fingerprintToken(safeStg) + "." + errType,
                        "Fault masked (fail-soft)",
                        "FaultMaskingLayerMonitor",
                        java.util.Map.of(
                                "stage", safeStg,
                                "count", n,
                                "last", last,
                                "note", safeMessage(note, 180),
                                "context", context == null ? "" : safeContext(context, 240)
                        ),
                        t
                );
            } catch (RuntimeException failure) {
                traceSuppressed("faultMask.debugEventEmit", failure);
                recordDebugEventFailure(failure);
            }
        }

        // Log spam 방지: 첫 발생 + 20회마다만 WARN
        if (n == 1 || n % 20 == 0) {
            log.warn("[FaultMask] stage={} count={} last={} note={}", safeStg, n, last, safeMessage(note, 180));
        } else {
            log.debug("[FaultMask] stage={} count={} last={} note={}", safeStg, n, last, safeMessage(note, 180));
        }
    }

    private static String summarize(Throwable t) {
        if (t == null) return "";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        if (msg == null) msg = "";
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        msg = safeMessage(msg, 180);
        String name = root.getClass().getSimpleName();
        return msg.isBlank() ? name : (name + ": " + msg);
    }

    private static String safeMessage(String s, int max) {
        String out = com.example.lms.trace.SafeRedactor.safeMessage(s, max);
        return out == null ? "" : out;
    }

    private static Object safeContext(String s, int max) {
        return com.example.lms.trace.SafeRedactor.diagnosticValue("faultmask.context.rawText", s, max);
    }

    private static void recordDebugEventFailure(RuntimeException failure) {
        try {
            TraceStore.inc("faultmask.debugEvent.emit.failed");
            TraceStore.put("faultmask.debugEvent.emit.failureClass", "faultmask_debug_event_emit_failed");
        } catch (RuntimeException traceError) {
            traceSuppressed("faultMask.debugEventFailureTrace", traceError);
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        traceFaultMaskSkipped(stage, error);
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("faultmask.suppressed." + safeStage, true);
    }

    private static void traceFaultMaskSkipped(String stage, Throwable error) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        log.debug("[FaultMask] telemetry skipped stage={} errorHash={} errorLength={}",
                safeStage,
                com.example.lms.trace.SafeRedactor.hashValue(messageOf(error)),
                messageLength(error));
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String safeLabel(String value, String fallback) {
        String label = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(value, fallback);
        return label == null || label.isBlank() ? fallback : label;
    }

    private static String fingerprintToken(String value) {
        String token = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return token.isBlank() ? "unknown" : token;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max) + "…";
    }
}
