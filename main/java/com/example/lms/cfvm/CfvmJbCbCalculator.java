package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CfvmJbCbCalculator {

    public JbCbResult calculate(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            TraceStore.put("cfvm.jb.score", 0.0d);
            TraceStore.put("cfvm.cb.score", 0.0d);
            TraceStore.put("cfvm.jb", 0.0d);
            TraceStore.put("cfvm.cb", 0.0d);
            traceLissajous(0.0d, 0.0d, false);
            return JbCbResult.EMPTY;
        }
        int planned = intValue(trace.get("chain.steps.planned"), 0);
        int executed = intValue(trace.get("chain.steps.executed"), 0);
        int failed = intValue(trace.get("chain.steps.failed"), 0);

        double jb = planned <= 0 ? 0.0d : clamp01((double) executed / planned);
        double cb = planned <= 0 ? 0.0d : clamp01((double) failed / planned);

        TraceStore.put("cfvm.jb.score", jb);
        TraceStore.put("cfvm.cb.score", cb);
        TraceStore.put("cfvm.jb", jb);
        TraceStore.put("cfvm.cb", cb);
        TraceStore.put("cfvm.jb.executed", Math.max(0, executed));
        TraceStore.put("cfvm.jb.planned", Math.max(0, planned));
        TraceStore.put("cfvm.cb.failed", Math.max(0, failed));
        traceLissajous(jb, cb, true);
        return new JbCbResult(jb, cb);
    }

    public record JbCbResult(double jb, double cb) {
        static final JbCbResult EMPTY = new JbCbResult(0.0d, 0.0d);
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? Math.max(0, (int) Math.round(parsed)) : fallback;
        } catch (NumberFormatException ex) {
            traceSuppressed("cfvm.jbcb.intValue", ex);
            return fallback;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("cfvm.jbcb.suppressed.stage", safeStage);
        TraceStore.put("cfvm.jbcb.suppressed." + safeStage, true);
        TraceStore.put("cfvm.jbcb.suppressed.errorType", errorType(ex));
    }

    private static void traceLissajous(double jb, double cb, boolean activated) {
        TraceStore.put("cfvm.detector.activated", activated);
        TraceStore.put("cfvm.lissajous.pattern", "jb_" + bucket(jb) + "_cb_" + bucket(cb));
    }

    private static String bucket(double value) {
        double v = clamp01(value);
        if (v >= 0.66d) {
            return "high";
        }
        if (v >= 0.33d) {
            return "mid";
        }
        return "low";
    }

    private static String errorType(RuntimeException ex) {
        if (ex instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ex == null ? "unknown" : ex.getClass().getSimpleName();
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
    }
}
