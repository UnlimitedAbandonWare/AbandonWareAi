package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses concurrent route triggers into one execution plan. The plan keeps
 * every requested stage visible while assigning a single primary mode.
 */
@Component
public class StrategyConflictResolver {
    private static final System.Logger LOG = System.getLogger(StrategyConflictResolver.class.getName());

    public record Signals(
            boolean lowRecall,
            boolean lowAuthority,
            boolean contradiction,
            boolean highRiskTail) {
    }

    public ExecutionPlan resolve(Signals signals) {
        if (signals == null) {
            ExecutionPlan normal = ExecutionPlan.normal();
            trace(normal);
            return normal;
        }

        List<String> triggers = new ArrayList<>(4);
        if (signals.lowRecall()) {
            triggers.add("lowRecall");
        }
        if (signals.lowAuthority()) {
            triggers.add("lowAuthority");
        }
        if (signals.contradiction()) {
            triggers.add("contradiction");
        }
        if (signals.highRiskTail()) {
            triggers.add("highRiskTail");
        }

        boolean requestedExtremeZ = signals.lowRecall();
        boolean requestedOverdrive = signals.lowAuthority() || signals.contradiction();
        boolean requestedHypernova = signals.highRiskTail();

        ExecutionPlan.PrimaryMode mode;
        if (requestedExtremeZ) {
            mode = ExecutionPlan.PrimaryMode.EXTREMEZ;
        } else if (requestedHypernova) {
            mode = ExecutionPlan.PrimaryMode.HYPERNOVA;
        } else if (requestedOverdrive) {
            mode = ExecutionPlan.PrimaryMode.OVERDRIVE;
        } else {
            mode = ExecutionPlan.PrimaryMode.NORMAL;
        }

        boolean extremeZ = mode == ExecutionPlan.PrimaryMode.EXTREMEZ;
        boolean overdrive = mode == ExecutionPlan.PrimaryMode.OVERDRIVE;
        boolean hypernova = mode == ExecutionPlan.PrimaryMode.HYPERNOVA;
        String suppressed = suppressedModes(mode, requestedExtremeZ, requestedOverdrive, requestedHypernova);

        Map<String, Object> knobs = new LinkedHashMap<>();
        knobs.put("extremeZ.aggressive", extremeZ);
        knobs.put("overdrive.aggressive", overdrive);
        knobs.put("gateChain.sharp", !triggers.isEmpty());
        knobs.put("breaker.failSoft", true);
        knobs.put("starvationLadder.deterministic", true);
        knobs.put("cancelShield.breadcrumb", !triggers.isEmpty());
        knobs.put("promptBuilder.required", true);
        knobs.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        knobs.put("specialMode.conflict.suppressed", suppressed);

        ExecutionPlan plan = new ExecutionPlan(
                mode,
                extremeZ,
                overdrive,
                hypernova,
                triggers,
                ExecutionPlan.DEFAULT_STAGES,
                knobs);
        trace(plan);
        return plan;
    }

    private static String suppressedModes(ExecutionPlan.PrimaryMode selected,
                                          boolean requestedExtremeZ,
                                          boolean requestedOverdrive,
                                          boolean requestedHypernova) {
        List<String> suppressed = new ArrayList<>(2);
        if (requestedOverdrive && selected != ExecutionPlan.PrimaryMode.OVERDRIVE) {
            suppressed.add("OVERDRIVE");
        }
        if (requestedExtremeZ && selected != ExecutionPlan.PrimaryMode.EXTREMEZ) {
            suppressed.add("EXTREMEZ");
        }
        if (requestedHypernova && selected != ExecutionPlan.PrimaryMode.HYPERNOVA) {
            suppressed.add("HYPERNOVA");
        }
        return String.join(",", suppressed);
    }

    private static void trace(ExecutionPlan plan) {
        try {
            TraceStore.put("routing.executionPlan.primaryMode", plan.primaryMode().name());
            TraceStore.put("routing.executionPlan.triggers", plan.triggers());
            TraceStore.put("routing.executionPlan.stages", plan.stages());
            TraceStore.put("routing.executionPlan.extremeZ", plan.extremeZEnabled());
            TraceStore.put("routing.executionPlan.overdrive", plan.overdriveEnabled());
            TraceStore.put("routing.executionPlan.hypernova", plan.hypernovaEnabled());
            TraceStore.put("routing.trigger.lowRecall", plan.triggers().contains("lowRecall"));
            TraceStore.put("routing.trigger.lowAuthority", plan.triggers().contains("lowAuthority"));
            TraceStore.put("routing.trigger.contradiction", plan.triggers().contains("contradiction"));
            TraceStore.put("routing.trigger.highRiskTail", plan.triggers().contains("highRiskTail"));
            for (Map.Entry<String, Object> entry : plan.knobs().entrySet()) {
                TraceStore.put("routing.executionPlan.knob." + entry.getKey(), entry.getValue());
            }
            Object suppressed = plan.knobs().get("specialMode.conflict.suppressed");
            List<String> suppressedModes = excludedModeList(suppressed);
            boolean overdriveDeferred = suppressedModes.contains("OVERDRIVE");
            boolean hypernovaDeferred = suppressedModes.contains("HYPERNOVA");
            Object priority = plan.knobs().get("specialMode.priority");
            String reason = conflictReason(plan.primaryMode(), suppressedModes);
            TraceStore.put("strategy.conflict.triggers", plan.triggers());
            TraceStore.put("strategy.conflict.primaryMode", plan.primaryMode().name());
            TraceStore.put("strategy.conflict.suppressed", suppressedModes);
            TraceStore.put("strategy.conflict.overdriveDeferred", overdriveDeferred);
            TraceStore.put("strategy.conflict.hypernovaDeferred", hypernovaDeferred);
            TraceStore.put("strategy.conflict.reason", reason);
            TraceStore.put("boosterMode.active", boosterModeName(plan.primaryMode()));
            TraceStore.put("boosterMode.excludedModes", suppressedModes);
            TraceStore.put("boosterMode.suppressed", suppressedModes);
            TraceStore.put("boosterMode.conflictResolved", !suppressedModes.isEmpty());
            TraceStore.put("boosterMode.priority", priority);
            TraceStore.put("boosterMode.exclusionReason",
                    "single_primary_mode:" + priority);
            if (suppressed != null && !String.valueOf(suppressed).isBlank()) {
                TraceStore.put("specialMode.conflict.suppressed", suppressed);
            }
            TraceStore.put("specialMode.priority", priority);
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "StrategyConflictResolver trace skipped errorType="
                            + SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
    }

    private static String boosterModeName(ExecutionPlan.PrimaryMode mode) {
        return mode == null || mode == ExecutionPlan.PrimaryMode.NORMAL ? "NONE" : mode.name();
    }

    private static String conflictReason(ExecutionPlan.PrimaryMode mode, List<String> suppressedModes) {
        if (suppressedModes == null || suppressedModes.isEmpty() || mode == null) {
            return "none";
        }
        return switch (mode) {
            case EXTREMEZ -> "extremeZ_priority";
            case HYPERNOVA -> "hypernova_priority";
            case OVERDRIVE -> "overdrive_priority";
            case NORMAL -> "none";
        };
    }

    private static List<String> excludedModeList(Object suppressed) {
        if (suppressed == null || String.valueOf(suppressed).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(suppressed).split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
