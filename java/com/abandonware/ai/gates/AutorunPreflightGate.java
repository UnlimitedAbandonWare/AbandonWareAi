package com.abandonware.ai.gates;

import java.util.*;

public class AutorunPreflightGate {
    public boolean check(Map<String, Object> ctx) {
        // basic checks: has authoritative sources, risk score < threshold, etc.
        Object auth = ctx.getOrDefault("authoritativeCount", 0);
        Object risk = ctx.getOrDefault("riskScore", 0.0);
        return ((int)auth) >= 1 && ((double)risk) < 0.8;
    }
}