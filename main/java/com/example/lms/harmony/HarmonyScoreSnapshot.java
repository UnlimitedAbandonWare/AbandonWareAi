package com.example.lms.harmony;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record HarmonyScoreSnapshot(
        Instant calculatedAt,
        double harmonyScore,
        double contaminationScore,
        double achievementPct,
        double goalPoint,
        Map<String, SubsystemScore> subsystemScores,
        List<HarmonyBreakEntry> harmonyBreaks,
        List<String> topContaminants,
        String nextGoalHint) {

    public HarmonyScoreSnapshot {
        calculatedAt = calculatedAt == null ? Instant.now() : calculatedAt;
        harmonyScore = clampPercent(harmonyScore);
        contaminationScore = clampPercent(contaminationScore);
        achievementPct = clampPercent(achievementPct);
        goalPoint = clampGoalPoint(goalPoint);
        subsystemScores = subsystemScores == null ? Map.of() : Map.copyOf(subsystemScores);
        harmonyBreaks = harmonyBreaks == null ? List.of() : List.copyOf(harmonyBreaks);
        topContaminants = topContaminants == null ? List.of() : List.copyOf(topContaminants);
        nextGoalHint = nextGoalHint == null ? "" : nextGoalHint;
    }

    public static Builder builder() {
        return new Builder();
    }

    public record SubsystemScore(
            String id,
            String name,
            double goal,
            double current) {
    }

    public record HarmonyBreakEntry(
            String id,
            String status,
            double penaltyScore,
            String evidence) {
    }

    public static final class Builder {
        private Instant calculatedAt = Instant.now();
        private double harmonyScore;
        private double contaminationScore;
        private double achievementPct;
        private double goalPoint = SubsystemGoalTable.totalGoalPoint();
        private Map<String, SubsystemScore> subsystemScores = Map.of();
        private List<HarmonyBreakEntry> harmonyBreaks = List.of();
        private List<String> topContaminants = List.of();
        private String nextGoalHint = "";

        private Builder() {
        }

        public Builder calculatedAt(Instant calculatedAt) {
            this.calculatedAt = calculatedAt == null ? Instant.now() : calculatedAt;
            return this;
        }

        public Builder harmonyScore(double harmonyScore) {
            this.harmonyScore = harmonyScore;
            return this;
        }

        public Builder contaminationScore(double contaminationScore) {
            this.contaminationScore = contaminationScore;
            return this;
        }

        public Builder achievementPct(double achievementPct) {
            this.achievementPct = achievementPct;
            return this;
        }

        public Builder goalPoint(double goalPoint) {
            this.goalPoint = goalPoint;
            return this;
        }

        public Builder subsystemScores(Map<String, SubsystemScore> subsystemScores) {
            this.subsystemScores = subsystemScores == null ? Map.of() : Map.copyOf(subsystemScores);
            return this;
        }

        public Builder harmonyBreaks(List<HarmonyBreakEntry> harmonyBreaks) {
            this.harmonyBreaks = harmonyBreaks == null ? List.of() : List.copyOf(harmonyBreaks);
            return this;
        }

        public Builder topContaminants(List<String> topContaminants) {
            this.topContaminants = topContaminants == null ? List.of() : List.copyOf(topContaminants);
            return this;
        }

        public Builder nextGoalHint(String nextGoalHint) {
            this.nextGoalHint = nextGoalHint == null ? "" : nextGoalHint;
            return this;
        }

        public HarmonyScoreSnapshot build() {
            return new HarmonyScoreSnapshot(
                    calculatedAt,
                    harmonyScore,
                    contaminationScore,
                    achievementPct,
                    goalPoint,
                    subsystemScores,
                    harmonyBreaks,
                    topContaminants,
                    nextGoalHint);
        }
    }

    private static double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }

    private static double clampGoalPoint(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return SubsystemGoalTable.totalGoalPoint();
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }
}
