package service.rag.calibration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal runtime score calibrator.
 * Supports per-source linear monotonic transform: calibrated = a*raw + b.
 * Default is identity.
 */
public class ScoreCalibrator {

    public enum SourceType { WEB, VECTOR, KG }

    public static class Params {
        public final double a;
        public final double b;
        public Params(double a, double b) { this.a = a; this.b = b; }
    }

    private final Map<SourceType, Params> table = new EnumMap<>(SourceType.class);

    public ScoreCalibrator() {
        // identity defaults
        for (SourceType t : SourceType.values()) {
            table.put(t, new Params(1.0, 0.0));
        }
    }

    public ScoreCalibrator(Map<SourceType, Params> overrides) {
        this();
        if (overrides != null) {
            table.putAll(overrides);
        }
    }

    public double apply(double rawScore, SourceType src) {
        Params p = table.getOrDefault(src, new Params(1.0, 0.0));
        return p.a * rawScore + p.b;
    }
}