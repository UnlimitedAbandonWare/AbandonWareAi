package com.example.lms.trace;

public class TraceContext {
    private boolean ruleBreak;
    private boolean zeroBreak;
    private Policy policy = new Policy();

    public boolean isRuleBreak(){ return ruleBreak; }
    public boolean isZeroBreak(){ return zeroBreak; }
    public Policy policy(){ return policy; }

    public void setRuleBreak(boolean v){ this.ruleBreak = v; }
    public void setZeroBreak(boolean v){ this.zeroBreak = v; }

    public static class Policy {
        private boolean whitelistBypass = false;
        public boolean whitelistBypass(){ return whitelistBypass; }
        public void setWhitelistBypass(boolean v){ this.whitelistBypass = v; }
    }
}