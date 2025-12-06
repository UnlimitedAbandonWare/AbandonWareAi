package com.example.lms.nova;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;




@Component("novaRuleBreakInterceptor")
public class RuleBreakInterceptor implements HandlerInterceptor {
    public static final String HDR = "X-RuleBreak-Token";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String token = req.getHeader(HDR);
        if (token != null && !token.isBlank()) {
            RuleBreakContext.Policy policy = parse(token);
            NovaRequestContext.setRuleBreak(new RuleBreakContext(true, policy, token, null));
        } else {
            NovaRequestContext.clearRuleBreak();
        }
        String brave = req.getHeader("X-Brave-Mode");
        if (brave != null && brave.equalsIgnoreCase("on")) {
            NovaRequestContext.setBrave(true);
        } else {
            NovaRequestContext.setBrave(false);
        }
        return true;
    }

    private RuleBreakContext.Policy parse(String token) {
        String t = token.toLowerCase();
        if (t.contains("all")) return RuleBreakContext.Policy.ALL_DOMAIN;
        if (t.contains("wide")) return RuleBreakContext.Policy.WIDE;
        return RuleBreakContext.Policy.FAST;
    }
}