package com.example.lms.nova;

import java.util.Map;
import java.util.function.Function;



public final class NovaWhitelist {
    private NovaWhitelist() {}

    public static boolean allowOfficial(String host, Function<String, Boolean> defaultCheck, Function<Map<String,Object>, Void> audit) {
        if (NovaRequestContext.hasRuleBreak()
                && NovaRequestContext.getRuleBreak().policy() == RuleBreakContext.Policy.ALL_DOMAIN) {
            if (audit != null) {
                audit.apply(Map.of("event", "whitelist_bypass", "host", host));
            }
            return true;
        }
        return defaultCheck.apply(host);
    }
}