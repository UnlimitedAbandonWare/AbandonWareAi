package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;

public final class BodeClamp {

    /** Smoothly clamps high-amplitude response into the [0,1] range. */
    public static double apply(double x, double c) {
        if (Double.isNaN(x)) {
            return 0.0d;
        }
        double safeC = Double.isFinite(c) && c > 0.0d ? c : 0.0d;
        if (Double.isInfinite(x)) {
            double y = safeC > 0.0d ? Math.copySign(1.0d / Math.sqrt(safeC), x) : x;
            return clampUnit(y);
        }
        double y = x;
        if (safeC > 0.0d) {
            double ax = Math.abs(x);
            double scaled = safeC * ax * ax;
            if (Double.isFinite(scaled)) {
                y = x / Math.sqrt(1.0d + scaled);
            } else {
                double invAbs = 1.0d / ax;
                y = Math.copySign(1.0d / Math.sqrt((invAbs * invAbs) + safeC), x);
            }
        }
        return clampUnit(y);
    }

    public static double applyTraced(double x, double c, String tracePrefix) {
        double result = apply(x, c);
        if (tracePrefix != null && !tracePrefix.isBlank()) {
            TraceStore.put(tracePrefix + ".bodeClamp.input", x);
            TraceStore.put(tracePrefix + ".bodeClamp.c", c);
            TraceStore.put(tracePrefix + ".bodeClamp.result", result);
        }
        return result;
    }

    private BodeClamp() {
    }

    private static double clampUnit(double y) {
        if (!Double.isFinite(y)) {
            return y > 0.0d ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, y));
    }
}
