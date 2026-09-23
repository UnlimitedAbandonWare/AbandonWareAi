package com.example.lms.plan;

import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanHintApplierKgFirstTest {

    @Test
    void kgFirstPlanKeepsOrderAndKgTopK() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        PlanHints hints = applier.load("kg_first.v1");

        assertEquals(List.of("kg", "vector", "web"), hints.retrievalOrder());
        assertEquals(8, hints.kgTopK());
    }

    @Test
    void zero100PlanParamsPassThroughToGuardContext() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        GuardContext ctx = new GuardContext();

        PlanHints hints = applier.load("zero100.v1");
        applier.applyToGuardContext(hints, ctx);

        assertTrue(ctx.planBool("search.zero100.enabled", false));
        assertEquals(55, ctx.planInt("search.zero100.crossVerifyStartPct", 0));
        assertEquals(80, ctx.planInt("search.zero100.consensusStartPct", 0));
        assertEquals(9, ctx.planInt("search.zero100.queryBurstMax", 0));
        assertEquals(1.0d, ctx.planDouble("search.zero100.strictWeight", 0.0d));
        assertEquals(1.0d, ctx.planDouble("search.zero100.relaxedWeight", 0.0d));
        assertEquals(1.0d, ctx.planDouble("search.zero100.exploreWeight", 0.0d));
        assertEquals(3, ctx.getMinCitations());
    }
}
