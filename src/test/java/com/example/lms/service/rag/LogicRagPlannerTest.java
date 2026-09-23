package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LogicRagPlannerTest {

    @Test
    void entityQueryRunsEntityLaneBeforeBackgroundAndReasoning() {
        LogicRagPlanner.Result result = new LogicRagPlanner().plan(
                "who is Ada and what aliases are related",
                seeds("RC", "reasoning", "BQ", "background", "ER", "entity"),
                5,
                true);

        assertEquals(List.of("ER", "BQ", "RC"), lanes(result));
        assertEquals("entity_first", result.trace().get("selfask.logicDag.dependencyMode"));
        assertEquals(List.of("ER", "BQ", "RC"), result.trace().get("selfask.logicDag.topologicalOrder"));
    }

    @Test
    void recencyQueryRunsCurrentFactsBeforeReasoningAndEntityExpansion() {
        LogicRagPlanner.Result result = new LogicRagPlanner().plan(
                "latest news today",
                seeds("ER", "entity", "RC", "counter", "BQ", "current facts"),
                5,
                true);

        assertEquals(List.of("BQ", "RC", "ER"), lanes(result));
        assertEquals("recency_first", result.trace().get("selfask.logicDag.dependencyMode"));
    }

    @Test
    void contrastQueryRunsBaseFactsAndEntitiesBeforeCorrectionLane() {
        LogicRagPlanner.Result result = new LogicRagPlanner().plan(
                "compare alpha vs beta and explain the difference",
                seeds("RC", "contrast", "ER", "entities", "BQ", "base facts"),
                5,
                true);

        assertEquals(List.of("BQ", "ER", "RC"), lanes(result));
        assertEquals("contrast_reasoning", result.trace().get("selfask.logicDag.dependencyMode"));
    }

    @Test
    void duplicatePruningKeepsStableFirstSeedAndRedactedTrace() {
        String rawQuery = "secret raw query";
        LogicRagPlanner.Result result = new LogicRagPlanner().plan(
                rawQuery,
                seeds("BQ", "Duplicate Seed!", "ER", "duplicate seed", "RC", "distinct seed"),
                5,
                true);

        assertEquals(List.of("BQ", "RC"), lanes(result));
        assertEquals(1, result.trace().get("selfask.logicDag.prunedDuplicateCount"));
        String traceDump = String.valueOf(result.trace());
        assertFalse(traceDump.contains(rawQuery));
        assertFalse(traceDump.contains("Duplicate Seed"));
        assertFalse(traceDump.contains("distinct seed"));
    }

    @Test
    void cycleDetectionFailsSoftToOriginalOrder() {
        LogicRagPlanner.Result result = new LogicRagPlanner(true).plan(
                "who is cyclic",
                seeds("RC", "reasoning", "BQ", "background", "ER", "entity"),
                5,
                true);

        assertEquals(List.of("RC", "BQ", "ER"), lanes(result));
        assertEquals("cycle_detected", result.trace().get("selfask.logicDag.failureClass"));
    }

    private static List<LogicRagPlanner.Seed> seeds(String lane1, String query1,
                                                    String lane2, String query2,
                                                    String lane3, String query3) {
        return List.of(
                new LogicRagPlanner.Seed(lane1, query1, 1.0d),
                new LogicRagPlanner.Seed(lane2, query2, 1.0d),
                new LogicRagPlanner.Seed(lane3, query3, 1.0d));
    }

    private static List<String> lanes(LogicRagPlanner.Result result) {
        return result.seeds().stream().map(LogicRagPlanner.Seed::lane).toList();
    }
}
