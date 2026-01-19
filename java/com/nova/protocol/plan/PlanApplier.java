package com.nova.protocol.plan;

import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.context.PlanContext;
import reactor.util.context.ContextView;



public class PlanApplier {

    private final PlanLoader loader;
    private final NovaProperties props;

    public PlanApplier(PlanLoader loader, NovaProperties props) {
        this.loader = loader; this.props = props;
    }

    public Plan resolvePlan(String planId, boolean brave) {
        String id = planId;
        if (brave) id = "brave.v1";
        Plan p = loader.loadFromClasspath(id);
        if (p == null) {
            p = loader.loadFromClasspath(props.getDefaultPlanId());
        }
        if (p == null) {
            // fallback default
            p = new Plan();
            p.setId("default");
        }
        return p;
    }

    public Plan currentPlan(ContextView reactorCtx) {
        if (reactorCtx.hasKey(PlanContext.KEY)) {
            return ((PlanContext) reactorCtx.get(PlanContext.KEY)).getPlan();
        }
        return loader.loadFromClasspath(props.getDefaultPlanId());
    }
}