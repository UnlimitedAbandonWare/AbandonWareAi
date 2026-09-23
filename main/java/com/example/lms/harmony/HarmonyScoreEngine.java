package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HarmonyScoreEngine {

    private final HarmonyBreakLedger breakLedger;
    private final ContaminationAccumulator contaminationAccumulator;
    private final HarmonyTraceReader traceReader;

    public HarmonyScoreEngine(HarmonyBreakLedger breakLedger, ContaminationAccumulator contaminationAccumulator) {
        this(breakLedger, contaminationAccumulator, new HarmonyTraceReader());
    }

    @Autowired
    public HarmonyScoreEngine(
            HarmonyBreakLedger breakLedger,
            ContaminationAccumulator contaminationAccumulator,
            HarmonyTraceReader traceReader) {
        this.breakLedger = breakLedger;
        this.contaminationAccumulator = contaminationAccumulator;
        this.traceReader = traceReader == null ? new HarmonyTraceReader() : traceReader;
    }

    public HarmonyScoreSnapshot compute() {
        try {
            return computeSafely();
        } catch (RuntimeException error) {
            TraceStore.put("harmony.score.compute.failed", Boolean.TRUE);
            TraceStore.put("harmony.score.compute.errorType", error.getClass().getSimpleName());
            return HarmonyScoreSnapshot.builder()
                    .calculatedAt(Instant.now())
                    .harmonyScore(0.0d)
                    .contaminationScore(100.0d)
                    .achievementPct(0.0d)
                    .goalPoint(SubsystemGoalTable.totalGoalPoint())
                    .nextGoalHint("evidence_needed: harmony score compute failed type="
                            + error.getClass().getSimpleName())
                    .build();
        }
    }

    private HarmonyScoreSnapshot computeSafely() {
        HarmonyTraceReader.TraceFrame frame = traceReader.readFrame();
        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = breakLedger.evaluate(frame);
        boolean promotionBlocked = breaks.stream()
                .anyMatch(entry -> !"DONE".equals(entry.status()));
        double harmonyScore = promotionBlocked
                ? 0.0d
                : clamp(100.0d + synergyBonus(frame));
        TraceStore.put("harmony.promotionAllowed", !promotionBlocked);
        TraceStore.put("harmony.evidenceStatus",
                promotionBlocked ? "BLOCKED_EVIDENCE" : "DONE");
        double contaminationScore = contaminationAccumulator.compute(frame);
        double goalPoint = SubsystemGoalTable.totalGoalPoint();
        double achievementPct = goalPoint > 0.0d ? clamp(harmonyScore / goalPoint * 100.0d) : 0.0d;

        return HarmonyScoreSnapshot.builder()
                .calculatedAt(Instant.now())
                .harmonyScore(harmonyScore)
                .contaminationScore(contaminationScore)
                .achievementPct(achievementPct)
                .goalPoint(goalPoint)
                .subsystemScores(subsystemScores(breaks))
                .harmonyBreaks(breaks)
                .topContaminants(contaminationAccumulator.topContaminants(7, frame))
                .nextGoalHint(nextGoalHint(breaks))
                .build();
    }

    private double synergyBonus(HarmonyTraceReader.TraceFrame frame) {
        double bonus = 0.0d;
        if (containsString(frame, "routing.executionPlan.primaryMode", "OVERDRIVE")
                && trueBoolean(frame, "extremeZ.cancelShieldWrapped")) {
            bonus += 5.0d;
        }
        if (finiteNumber(frame, "cfvm.boltzmannTemp")
                && trueBoolean(frame, "moe.evolverPlateRegistered")) {
            bonus += 4.0d;
        }
        if (finiteNumber(frame, "hypernova.twpmP")
                && trueBoolean(frame, "cihRag.breadcrumb.queryRedacted")) {
            bonus += 4.0d;
        }
        return Math.min(13.0d, bonus);
    }

    private Map<String, HarmonyScoreSnapshot.SubsystemScore> subsystemScores(
            List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        Map<String, Double> current = new LinkedHashMap<>();
        SubsystemGoalTable.GOALS.forEach((id, goal) -> current.put(id, goal.goalPoint()));

        for (HarmonyScoreSnapshot.HarmonyBreakEntry entry : breaks) {
            if ("DONE".equals(entry.status())) {
                continue;
            }
            String subsystem = breakLedger.subsystemFor(entry.id());
            if (subsystem == null || !current.containsKey(subsystem)) {
                continue;
            }
            double goal = SubsystemGoalTable.GOALS.get(subsystem).goalPoint();
            double next = Math.max(0.0d, current.get(subsystem) - (entry.penaltyScore() * goal / 100.0d));
            current.put(subsystem, next);
        }

        Map<String, HarmonyScoreSnapshot.SubsystemScore> out = new LinkedHashMap<>();
        SubsystemGoalTable.GOALS.forEach((id, goal) -> out.put(id,
                new HarmonyScoreSnapshot.SubsystemScore(
                        id,
                        goal.name(),
                        round2(goal.goalPoint()),
                        round2(current.getOrDefault(id, 0.0d)))));
        return out;
    }

    private static String nextGoalHint(List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        return breaks.stream()
                .filter(entry -> !"DONE".equals(entry.status()))
                .max(Comparator.comparingDouble(HarmonyScoreSnapshot.HarmonyBreakEntry::penaltyScore))
                .map(entry -> "BLOCKED_EVIDENCE: " + entry.id() + " (" + entry.evidence() + ")")
                .orElse("All HB checks are DONE; maintain harmony score and monitor contamination.");
    }

    private boolean finiteNumber(HarmonyTraceReader.TraceFrame frame, String key) {
        Object value = read(frame, key);
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private boolean trueBoolean(HarmonyTraceReader.TraceFrame frame, String key) {
        Object value = read(frame, key);
        return Boolean.TRUE.equals(value);
    }

    private boolean containsString(HarmonyTraceReader.TraceFrame frame, String key, String fragment) {
        Object value = read(frame, key);
        return value instanceof String text && text.contains(fragment);
    }

    private Object read(HarmonyTraceReader.TraceFrame frame, String key) {
        try {
            HarmonyTraceReader.TraceFrame selected = frame == null
                    ? HarmonyTraceReader.TraceFrame.missing()
                    : frame;
            return selected.read(key).value();
        } catch (RuntimeException ignored) {
            TraceStore.put("harmony.score.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.score.traceRead.key", key);
            TraceStore.put("harmony.score.traceRead.errorType", ignored.getClass().getSimpleName());
            return null;
        }
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
