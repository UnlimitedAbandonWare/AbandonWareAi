package com.nova.protocol.context;

import com.nova.protocol.plan.Plan;



public final class PlanContext {
    public static final String KEY = "nova.plan.ctx";
    private final Plan plan;
    public PlanContext(Plan p) { this.plan = p; }
    public Plan getPlan() { return plan; }
}