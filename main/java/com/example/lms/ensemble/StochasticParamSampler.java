package com.example.lms.ensemble;

import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Draws three pills from a 15 caffeine / 15 theanine pool and maps the draw to
 * sampling parameters for the stochastic ensemble node.
 */
@Component
public class StochasticParamSampler {

    private static final int POOL_CAFFEINE = 15;
    private static final int POOL_THEANINE = 15;
    private static final int DRAW_COUNT = 3;

    private final SelectionEntropy fallbackEntropy;

    public StochasticParamSampler() {
        this(SelectionEntropyFactory.standard());
    }

    StochasticParamSampler(DoubleSupplier boundedRandom) {
        this(new SupplierSelectionEntropy(boundedRandom));
    }

    private StochasticParamSampler(SelectionEntropy fallbackEntropy) {
        this.fallbackEntropy = fallbackEntropy;
    }

    public record DrawResult(double temperature, double topP, int caffeine, int theanine) {
    }

    /** Internal request-local profile. Selector and jitter are intentionally absent. */
    record CreativeProfile(
            String label,
            double searchTemperature,
            double searchExplorationRate,
            double candidateTemperature,
            double candidateTopP,
            double finalTemperature,
            double finalTopP,
            double selfAskTemperature,
            String requestedOptionsHash) {
    }

    Optional<CreativeProfile> drawCreativeProfile() {
        SelectionRuntime selection = currentSelection();
        return drawCreativeProfile(selection.entropy(), selection.ledger());
    }

    private Optional<CreativeProfile> drawCreativeProfile(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger) {
        try {
            String actor = "profile:creative-emergence";
            SelectionCoordinate selectorCoordinate = new SelectionCoordinate(
                    "ensemble.profile.selector", actor, 0L, 0L);
            double selector = unit(entropy, selectorCoordinate);
            String label = selector < 0.45d ? "VIVID" : selector < 0.85d ? "WILD" : "FERAL";
            int labelIndex = "VIVID".equals(label) ? 0 : "WILD".equals(label) ? 1 : 2;
            ledger.record(SelectionDecisionLedger.Lane.ENSEMBLE,
                    selectorCoordinate, List.of("vivid", "wild", "feral"),
                    labelIndex, "", true, false);

            double searchTemperature = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.search-temperature", actor, label),
                    0.85d, 0.90d, 0.91d, 0.97d, 0.98d, 1.00d);
            double searchRate = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.search-rate", actor, label),
                    0.70d, 0.76d, 0.77d, 0.83d, 0.84d, 0.85d);
            double candidateTemperature = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.candidate-temperature", actor, label),
                    1.10d, 1.25d, 1.26d, 1.45d, 1.46d, 1.50d);
            double candidateTopP = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.candidate-top-p", actor, label),
                    0.95d, 0.97d, 0.97d, 0.99d, 0.99d, 1.00d);
            double finalTemperature = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.final-temperature", actor, label),
                    1.05d, 1.20d, 1.21d, 1.40d, 1.41d, 1.50d);
            double finalTopP = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.final-top-p", actor, label),
                    0.95d, 0.97d, 0.97d, 0.99d, 0.99d, 1.00d);
            double selfAskTemperature = value(label,
                    profileUnit(entropy, ledger, "ensemble.profile.self-ask-temperature", actor, label),
                    0.80d, 0.88d, 0.89d, 0.97d, 0.98d, 1.00d);
            String canonical = String.format(Locale.ROOT,
                    "%s|%.2f|%.2f|%.2f|%.2f|%.2f|%.2f|%.2f",
                    label, searchTemperature, searchRate, candidateTemperature, candidateTopP,
                    finalTemperature, finalTopP, selfAskTemperature);
            return Optional.of(new CreativeProfile(
                    label,
                    searchTemperature,
                    searchRate,
                    candidateTemperature,
                    candidateTopP,
                    finalTemperature,
                    finalTopP,
                    selfAskTemperature,
                    SafeRedactor.hashValue(canonical)));
        } catch (SelectionEntropyException failure) {
            ledger.markFailure(failure.reason());
            throw failure;
        } catch (RuntimeException failure) {
            SelectionEntropyReason reason = SelectionEntropyReason.DERIVATION_INVALID;
            ledger.markFailure(reason);
            TraceStore.put("ensemble.stoch.draw.failureReason", reason.code());
            TraceStore.inc("ensemble.stoch.draw.failureCount");
            return Optional.empty();
        }
    }

    /** Applies a complete internal profile without exporting its data carrier. */
    public boolean applyCreativeProfile(GuardContext context) {
        if (context == null) {
            return false;
        }
        SelectionRuntime selection = selectionFor(context);
        Optional<CreativeProfile> profile = drawCreativeProfile(
                selection.entropy(), selection.ledger());
        if (profile.isEmpty()) {
            return false;
        }
        CreativeProfile p = profile.get();
        context.putPlanOverride("creative.emergence.profile", p.label());
        context.putPlanOverride("creative.emergence.search.temperature", p.searchTemperature());
        context.putPlanOverride("creative.emergence.search.rate", p.searchExplorationRate());
        context.putPlanOverride("creative.emergence.candidate.temperature", p.candidateTemperature());
        context.putPlanOverride("creative.emergence.candidate.topP", p.candidateTopP());
        context.putPlanOverride("creative.emergence.final.temperature", p.finalTemperature());
        context.putPlanOverride("creative.emergence.final.topP", p.finalTopP());
        context.putPlanOverride("creative.emergence.selfAsk.temperature", p.selfAskTemperature());
        context.putPlanOverride("creative.emergence.requestedOptionsHash", p.requestedOptionsHash());
        return true;
    }

    public DrawResult draw(String traceId) {
        SelectionRuntime selection = currentSelection();
        return draw(traceId, selection.entropy(), selection.ledger(), "node:opportunistic");
    }

    DrawResult draw(
            String traceId,
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            String actorKey) {
        int[] pool = new int[POOL_CAFFEINE + POOL_THEANINE];
        for (int i = 0; i < POOL_CAFFEINE; i++) {
            pool[i] = 1;
        }

        try {
            for (int i = pool.length - 1; i > 0; i--) {
                SelectionCoordinate coordinate = new SelectionCoordinate(
                        "ensemble.profile.shuffle", actorKey, 0L, pool.length - 1L - i);
                int j = entropy.boundedIndex(coordinate, i + 1);
                ledger.record(SelectionDecisionLedger.Lane.ENSEMBLE,
                        coordinate, slotKeys(i + 1), j, "", true, false);
                int tmp = pool[i];
                pool[i] = pool[j];
                pool[j] = tmp;
            }
        } catch (SelectionEntropyException failure) {
            ledger.markFailure(failure.reason());
            throw failure;
        }

        int caffeine = 0;
        for (int i = 0; i < DRAW_COUNT; i++) {
            caffeine += pool[i];
        }
        DrawResult result = mapComposition(caffeine);

        String traceHash = SafeRedactor.hash12(traceId == null ? "unknown" : traceId.trim());
        String safeTraceId = traceHash == null || traceHash.isBlank() ? "unknown" : traceHash;
        TraceStore.put("ensemble.stoch.draw." + safeTraceId, String.format(Locale.ROOT,
                "caffeine=%d,theanine=%d,temp=%.2f,top_p=%.2f",
                result.caffeine(), result.theanine(), result.temperature(), result.topP()));
        return result;
    }

    private SelectionRuntime currentSelection() {
        return selectionFor(GuardContextHolder.get());
    }

    private SelectionRuntime selectionFor(GuardContext context) {
        return context != null && context.hasAttachedSelectionEntropy()
                ? new SelectionRuntime(context.selectionEntropy(), context.selectionDecisionLedger())
                : new SelectionRuntime(fallbackEntropy, SelectionDecisionLedger.forStandard());
    }

    private static double profileUnit(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            String decisionKey,
            String actor,
            String label) {
        SelectionCoordinate coordinate = new SelectionCoordinate(decisionKey, actor, 0L, 0L);
        double value = unit(entropy, coordinate);
        ledger.record(SelectionDecisionLedger.Lane.ENSEMBLE,
                coordinate, List.of(label.toLowerCase(Locale.ROOT)), 0, "", true, false);
        return value;
    }

    private static double unit(SelectionEntropy entropy, SelectionCoordinate coordinate) {
        double value = entropy.unitInterval(coordinate);
        if (!boundedUnit(value)) {
            throw new InvalidSupplierValueException();
        }
        return value;
    }

    private static List<String> slotKeys(int bound) {
        List<String> keys = new ArrayList<>(bound);
        for (int i = 0; i < bound; i++) {
            keys.add("slot:" + i);
        }
        return List.copyOf(keys);
    }

    static DrawResult mapComposition(int caffeine) {
        int theanine = DRAW_COUNT - caffeine;
        return switch (caffeine) {
            case 3 -> new DrawResult(0.20d, 0.30d, caffeine, theanine);
            case 2 -> new DrawResult(0.55d, 0.60d, caffeine, theanine);
            case 1 -> new DrawResult(1.1d, 0.85d, caffeine, theanine);
            default -> new DrawResult(1.4d, 0.95d, caffeine, theanine);
        };
    }

    private static boolean boundedUnit(double value) {
        return Double.isFinite(value) && value >= 0.0d && value < 1.0d;
    }

    private static double value(
            String label,
            double jitter,
            double vividMin,
            double vividMax,
            double wildMin,
            double wildMax,
            double feralMin,
            double feralMax) {
        double min = "VIVID".equals(label) ? vividMin : "WILD".equals(label) ? wildMin : feralMin;
        double max = "VIVID".equals(label) ? vividMax : "WILD".equals(label) ? wildMax : feralMax;
        return quantize(min + jitter * (max - min));
    }

    private static double quantize(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record SelectionRuntime(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger) {
    }

    private static final class SupplierSelectionEntropy implements SelectionEntropy {
        private final DoubleSupplier supplier;

        private SupplierSelectionEntropy(DoubleSupplier supplier) {
            this.supplier = supplier == null ? () -> Double.NaN : supplier;
        }

        @Override
        public SelectionEntropyMode mode() {
            return SelectionEntropyMode.STANDARD;
        }

        @Override
        public String algorithmVersion() {
            return SelectionEntropyFactory.standard().algorithmVersion();
        }

        @Override
        public double unitInterval(SelectionCoordinate coordinate) {
            requireCoordinate(coordinate);
            double value = supplier.getAsDouble();
            if (!boundedUnit(value)) {
                throw new InvalidSupplierValueException();
            }
            return value;
        }

        @Override
        public int boundedIndex(SelectionCoordinate coordinate, int bound) {
            requireCoordinate(coordinate);
            if (bound < 1 || bound > 1_000_000) {
                throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
            }
            return (int) Math.floor(unitInterval(coordinate) * bound);
        }

        private static void requireCoordinate(SelectionCoordinate coordinate) {
            if (coordinate == null) {
                throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
            }
        }
    }

    private static final class InvalidSupplierValueException extends RuntimeException {
        private InvalidSupplierValueException() {
            super("selection_entropy_supplier_invalid");
        }
    }
}
