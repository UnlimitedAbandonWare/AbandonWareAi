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

    // 동적 스테이지 힌트 knob 키 — 실행 계획 단계 구성은 건드리지 않고 조언 신호만 기록한다.
    public static final String KNOB_STAGE_HINT_SKIP = "stageHints.skip";
    public static final String KNOB_STAGE_HINT_DEFERRED = "stageHints.deferredModes";
    public static final String KNOB_STAGE_HINT_PARALLEL_VERIFY = "stageHints.parallelVerify";
    public static final String KNOB_STAGE_HINT_FAST_EMIT = "stageHints.fastEmit";
    public static final String KNOB_STAGE_HINT_RETRY_ON_EMPTY = "stageHints.retryOnEmpty";

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
