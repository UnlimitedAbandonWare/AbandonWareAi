package com.nova.protocol.context;

import com.nova.protocol.rulebreak.RuleBreakPolicy;
import reactor.util.context.ContextView;



public final class RuleBreakContext {
    public static final String KEY = "nova.rulebreak.ctx";
    private final RuleBreakPolicy policy;
    private final long issuedAtEpochSec;

    public RuleBreakContext(RuleBreakPolicy policy, long issuedAtEpochSec) {
        this.policy = policy; this.issuedAtEpochSec = issuedAtEpochSec;
    }
    public RuleBreakPolicy getPolicy() { return policy; }
    public long getIssuedAtEpochSec() { return issuedAtEpochSec; }

    public static boolean isActive(ContextView ctx) {
        return ctx.hasKey(KEY);
    }
}