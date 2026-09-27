package com.nova.protocol.context;

import reactor.util.context.ContextView;



public final class BraveContext {
    public static final String KEY = "nova.brave.enabled";
    private final boolean enabled;
    private final String planId;

    public BraveContext(boolean enabled, String planId) {
        this.enabled = enabled; this.planId = planId;
    }
    public boolean isEnabled() { return enabled; }
    public String getPlanId() { return planId; }

    public static boolean isOn(ContextView ctx) {
        return ctx.hasKey(KEY) && ctx.get(KEY) instanceof BraveContext && ((BraveContext) ctx.get(KEY)).enabled;
    }
}