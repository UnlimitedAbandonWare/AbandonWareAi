package com.example.lms.harmony;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SubsystemGoalTable {

    public static final Map<String, SubsystemGoal> GOALS;

    static {
        Map<String, SubsystemGoal> goals = new LinkedHashMap<>();
        goals.put("S01", new SubsystemGoal("Overdrive / Anchor Compression", 0.18d,
                "Crisis-time context compression and anchor retention"));
        goals.put("S02", new SubsystemGoal("CFVM / Failure Pattern Analysis", 0.14d,
                "Failure memory pressure informs recovery paths"));
        goals.put("S03", new SubsystemGoal("MoE Strategy Selector", 0.16d,
                "Strategy choice stays evidence-aligned"));
        goals.put("S04", new SubsystemGoal("Matryoshka / ZCA", 0.10d,
                "Embedding slicing and whitening remain controlled"));
        goals.put("S05", new SubsystemGoal("ExtremeZ / Massive Parallel", 0.12d,
                "Burst retrieval stays cancellation-safe"));
        goals.put("S06", new SubsystemGoal("HYPERNOVA TWPM+CVaR", 0.14d,
                "Sparse high-value signals are fused safely"));
        goals.put("S07", new SubsystemGoal("CIH-RAG / MLA Breadcrumb", 0.10d,
                "Breadcrumbs are useful and redacted"));
        goals.put("S08", new SubsystemGoal("OpenAI Adapter / Version Purity", 0.06d,
                "Model routing and dependency purity stay stable"));
        GOALS = Collections.unmodifiableMap(goals);
    }

    private SubsystemGoalTable() {
    }

    public static double totalGoalPoint() {
        return GOALS.values().stream()
                .mapToDouble(goal -> goal.goalPoint())
                .sum();
    }

    public record SubsystemGoal(String name, double weight, String description) {
        public double goalPoint() {
            return weight * 100.0d;
        }
    }
}
