package com.abandonware.ai.agent.service.plan;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRegistryTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void currentFallsBackWhenLoaderReturnsNoPlans() {
        PlanRegistry registry = registryWith(Map.of());
        registry.init();

        RetrievalPlan plan = registry.current();

        assertThat(plan).isNotNull();
        assertThat(plan.id()).isEqualTo("safe.v1");
        assertThat(TraceStore.get("agent.planRegistry.fallback")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.planRegistry.fallback.reason")).isEqualTo("missing_active_plan");
    }

    @Test
    void byIdFallsBackBeforeInitInsteadOfThrowing() {
        PlanRegistry registry = registryWith(Map.of());

        RetrievalPlan plan = registry.byId("missing");

        assertThat(plan).isNotNull();
        assertThat(plan.id()).isEqualTo("safe.v1");
    }

    private static PlanRegistry registryWith(Map<String, RetrievalPlan> plans) {
        PlanRegistry registry = new PlanRegistry(new PlanLoader() {
            @Override
            public Map<String, RetrievalPlan> loadAll(String locationPattern) {
                return plans;
            }
        });
        ReflectionTestUtils.setField(registry, "activeId", "safe.v1");
        ReflectionTestUtils.setField(registry, "plansPath", "classpath:test-plans/*.yaml");
        return registry;
    }
}
