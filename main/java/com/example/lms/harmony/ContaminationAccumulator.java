package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Component
public class ContaminationAccumulator {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("starvation_outCount", "outCount", 20.0d, SignalType.ZERO_BAD),
            new Signal("ablation_penalties", "ablation.penalties", 25.0d, SignalType.LIST_SIZE_SCALED),
            new Signal("provider_all_skipped", "starvationFallback.trigger", 15.0d, SignalType.NONZERO_BAD),
            new Signal("qt_bypassed", "queryTransformer.bypassed", 10.0d, SignalType.NONZERO_BAD),
            new Signal("cancel_not_shielded", "extremeZ.cancelShieldWrapped", 10.0d, SignalType.BOOLEAN_FALSE_BAD),
            new Signal("breadcrumb_not_redacted", "cihRag.breadcrumb.queryRedacted", 10.0d, SignalType.BOOLEAN_FALSE_BAD),
            new Signal("booster_conflict", "boosterMode.exclusionReason", 10.0d, SignalType.NONZERO_BAD));

    private final HarmonyTraceReader traceReader;

    public ContaminationAccumulator() {
        this(new HarmonyTraceReader());
    }

    @Autowired
    public ContaminationAccumulator(HarmonyTraceReader traceReader) {
        this.traceReader = traceReader == null ? new HarmonyTraceReader() : traceReader;
    }

    public double compute() {
        return compute(traceReader.readFrame());
    }

    double compute(HarmonyTraceReader.TraceFrame frame) {
        double total = SIGNALS.stream()
                .mapToDouble(signal -> score(signal, frame))
                .sum();
        return clamp(total);
    }

    public List<String> topContaminants(int limit) {
        return topContaminants(limit, traceReader.readFrame());
    }

    List<String> topContaminants(int limit, HarmonyTraceReader.TraceFrame frame) {
        int boundedLimit = Math.max(0, limit);
        return SIGNALS.stream()
                .map(signal -> new ScoredSignal(signal.name(), score(signal, frame)))
                .filter(signal -> signal.score() > 0.0d)
                .sorted(Comparator.comparingDouble(ScoredSignal::score).reversed())
                .limit(boundedLimit)
                .map(ScoredSignal::name)
                .toList();
    }

    private Object safeGet(String key, HarmonyTraceReader.TraceFrame frame) {
        try {
            HarmonyTraceReader.TraceFrame selected = frame == null
                    ? HarmonyTraceReader.TraceFrame.missing()
                    : frame;
            return selected.read(key).value();
        } catch (RuntimeException ignored) {
            TraceStore.put("harmony.contamination.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.contamination.traceRead.key", key);
            TraceStore.put("harmony.contamination.traceRead.errorType", ignored.getClass().getSimpleName());
            return null;
        }
    }

    private double score(Signal signal, HarmonyTraceReader.TraceFrame frame) {
        if ("booster_conflict".equals(signal.name())) {
            return boosterConflictScore(signal, frame);
        }
        return score(signal, safeGet(signal.traceKey(), frame));
    }

    private double boosterConflictScore(Signal signal, HarmonyTraceReader.TraceFrame frame) {
        Object value = safeGet(signal.traceKey(), frame);
        Object conflictResolved = safeGet("boosterMode.conflictResolved", frame);
        if (isResolvedSinglePrimaryMode(value)
                || isNormalSinglePrimaryHash(value)
                || Boolean.TRUE.equals(conflictResolved)) {
            return 0.0d;
        }
        return score(signal, value);
    }

    private static double score(Signal signal, Object value) {
        if ("booster_conflict".equals(signal.name()) && isResolvedSinglePrimaryMode(value)) {
            return 0.0d;
        }
        return switch (signal.type()) {
            case ZERO_BAD -> isZero(value) ? signal.weight() : 0.0d;
            case NONZERO_BAD -> isNonZeroPresent(value) ? signal.weight() : 0.0d;
            case BOOLEAN_FALSE_BAD -> value instanceof Boolean bool && !bool ? signal.weight() : 0.0d;
            case LIST_SIZE_SCALED -> listScaled(signal.weight(), value);
        };
    }

    private static boolean isResolvedSinglePrimaryMode(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return text.trim().startsWith("single_primary_mode:");
    }

    private static boolean isNormalSinglePrimaryHash(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return text.trim().startsWith("hash:normal-single-primary");
    }

    private static boolean isZero(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() == 0.0d;
    }

    private static boolean isNonZeroPresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) && number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return false;
    }

    private static double listScaled(double weight, Object value) {
        if (value instanceof Collection<?> collection) {
            return Math.min(weight, weight * collection.size() / 10.0d);
        }
        return 0.0d;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 100.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }

    private enum SignalType {
        BOOLEAN_FALSE_BAD,
        NONZERO_BAD,
        ZERO_BAD,
        LIST_SIZE_SCALED
    }

    private record Signal(String name, String traceKey, double weight, SignalType type) {
    }

    private record ScoredSignal(String name, double score) {
    }
}
