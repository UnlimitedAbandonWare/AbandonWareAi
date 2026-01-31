package com.abandonwareai.zerobreak.gate;

import com.abandonwareai.zerobreak.context.ZeroBreakContext;
import java.util.Set;

/** Lightweight preflight checks before tool execution or risky actions. */
public class AutorunPreflightGate {
    private final Set<String> allowedPolicies = Set.of("recency","max_recall","speed_first","wide_web");

    public boolean check(ZeroBreakContext ctx) {
        // Example: policy whitelist check
        boolean policiesOk = ctx.getPolicies().stream().allMatch(allowedPolicies::contains);
        // Example: plan sanity
        boolean planOk = ctx.getPlanId() != null && !ctx.getPlanId().isBlank();
        return policiesOk && planOk;
    }
}