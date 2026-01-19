package com.abandonware.ai.normalization.guard;

import java.util.*;

public class AutorunPreflightGate {
    public static class Context {
        public int citations;
        public double authority; // 0..1
    }
    private final boolean enabled;
    private final int minCitations;
    private final double minAuthority;
    public AutorunPreflightGate(boolean enabled, int minCitations, double minAuthority) {
        this.enabled = enabled;
        this.minCitations = minCitations;
        this.minAuthority = minAuthority;
    }
    public boolean allow(Context ctx) {
        if (!enabled) return true;
        if (ctx == null) return false;
        if (ctx.citations < minCitations) return false;
        if (ctx.authority < minAuthority) return false;
        return true;
    }
}