package com.example.lms.orchestration;

import java.util.List;
import java.util.Map;

/**
 * One request-scoped routing plan. It can enable multiple sub-stages, but it
 * exposes only one primary mode so Overdrive/ExtremeZ/HYPERNOVA do not compete.
 */
public record ExecutionPlan(
        PrimaryMode primaryMode,
        boolean extremeZEnabled,
        boolean overdriveEnabled,
        boolean hypernovaEnabled,
        List<String> triggers,
        List<String> stages,
        Map<String, Object> knobs) {

    public static final List<String> DEFAULT_STAGES = List.of(
            "SelfAsk",
            "QueryBurst",
            "ExtremeZBurst",
            "OverdriveNarrow",
            "Grandas/RRF",
            "BiEncoder",
            "DPP",
            "ONNX CrossEncoder",
            "GateChain");

    public ExecutionPlan {
        primaryMode = primaryMode == null ? PrimaryMode.NORMAL : primaryMode;
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        stages = stages == null || stages.isEmpty() ? DEFAULT_STAGES : List.copyOf(stages);
        knobs = knobs == null ? Map.of() : Map.copyOf(knobs);
    }

    public static ExecutionPlan normal() {
        return new ExecutionPlan(PrimaryMode.NORMAL, false, false, false, List.of(), DEFAULT_STAGES, Map.of());
    }

    public enum PrimaryMode {
        NORMAL,
        EXTREMEZ,
        OVERDRIVE,
        HYPERNOVA
    }
}
