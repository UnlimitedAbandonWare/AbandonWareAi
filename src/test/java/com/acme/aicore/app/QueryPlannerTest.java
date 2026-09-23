package com.acme.aicore.app;

import com.acme.aicore.domain.model.Plan;
import com.acme.aicore.domain.model.UserQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlannerTest {

    @Test
    void nullQueryFallsBackToDefaultMixedPlan() {
        QueryPlanner planner = new QueryPlanner();

        Plan plan = planner.decide(null);

        assertThat(plan.useWeb()).isTrue();
        assertThat(plan.useVector()).isTrue();
        assertThat(plan.rerankTopN()).isEqualTo(10);
    }

    @Test
    void nullQueryTextFallsBackToDefaultMixedPlan() {
        QueryPlanner planner = new QueryPlanner();

        Plan plan = planner.decide(UserQuery.of(null));

        assertThat(plan.useWeb()).isTrue();
        assertThat(plan.useVector()).isTrue();
        assertThat(plan.rerankTopN()).isEqualTo(10);
    }
}
