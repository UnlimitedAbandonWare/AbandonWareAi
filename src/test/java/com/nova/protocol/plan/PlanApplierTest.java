package com.nova.protocol.plan;

import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.context.PlanContext;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanApplierTest {

    @Test
    void currentPlanFallsBackToDefaultWhenContextIsNull() {
        Plan plan = applierWithDefault("safe.v1").currentPlan(null);

        assertEquals("safe.v1", plan.getId());
        assertEquals(2, plan.getCitationMin());
    }

    @Test
    void currentPlanIgnoresMalformedContextValue() {
        Plan plan = applierWithDefault("safe.v1")
                .currentPlan(Context.of(PlanContext.KEY, "not-a-plan-context"));

        assertEquals("safe.v1", plan.getId());
    }

    @Test
    void currentPlanUsesValidPlanContext() {
        Plan explicit = new Plan();
        explicit.setId("explicit");

        Plan plan = applierWithDefault("safe.v1")
                .currentPlan(Context.of(PlanContext.KEY, new PlanContext(explicit)));

        assertEquals("explicit", plan.getId());
    }

    private static PlanApplier applierWithDefault(String defaultPlanId) {
        NovaProperties props = new NovaProperties();
        props.setDefaultPlanId(defaultPlanId);
        return new PlanApplier(new PlanLoader(), props);
    }
}
