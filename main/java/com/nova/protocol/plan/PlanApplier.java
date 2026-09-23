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
        Plan p = load(id);
        if (p == null) {
            p = load(defaultPlanId());
        }
        if (p == null) {
            // fallback default
            p = new Plan();
            p.setId(defaultPlanId());
        }
        return p;
    }

    public Plan currentPlan(ContextView reactorCtx) {
        if (reactorCtx != null && reactorCtx.hasKey(PlanContext.KEY)) {
            Object value = reactorCtx.get(PlanContext.KEY);
            if (value instanceof PlanContext planContext && planContext.getPlan() != null) {
                return planContext.getPlan();
            }
            if (value instanceof Plan plan) {
                return plan;
            }
        }
        return resolvePlan(defaultPlanId(), false);
    }

    private Plan load(String id) {
        return loader == null ? null : loader.loadFromClasspath(id);
    }

    private String defaultPlanId() {
        if (props == null || props.getDefaultPlanId() == null || props.getDefaultPlanId().isBlank()) {
            return "default";
        }
        return props.getDefaultPlanId();
    }
}
