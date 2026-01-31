package com.example.lms.guard.rulebreak;

import lombok.Getter;



@Getter
public enum RuleBreakPolicy {
    SAFE_EXPLORE(true, false, true, 2000),
    OVERRIDE_DOMAINS(true, true,  true, 3000),
    SPEED_FIRST(false,false, true, 0);

    private final boolean allowTopKBoost;
    private final boolean allowWhitelistBypass;
    private final boolean allowHedgeDisable;
    private final int extraTimeoutMs;

    RuleBreakPolicy(boolean allowTopKBoost, boolean allowWhitelistBypass, boolean allowHedgeDisable, int extraTimeoutMs) {
        this.allowTopKBoost = allowTopKBoost;
        this.allowWhitelistBypass = allowWhitelistBypass;
        this.allowHedgeDisable = allowHedgeDisable;
        this.extraTimeoutMs = extraTimeoutMs;
    }

    public boolean allowsTopKBoost() { return allowTopKBoost; }
    public boolean canBypassWhitelist() { return allowWhitelistBypass; }
    public boolean allowsHedgeDisable() { return allowHedgeDisable; }
}