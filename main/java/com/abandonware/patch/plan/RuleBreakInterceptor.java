package com.abandonware.patch.plan;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class RuleBreakInterceptor implements HandlerInterceptor {
    public static final String ATTR = "RB_CONTEXT";
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        RuleBreakContext ctx = new RuleBreakContext();
        ctx.braveMode = "on".equalsIgnoreCase(req.getHeader("X-Brave-Mode"));
        ctx.ruleBreakToken = req.getHeader("X-RuleBreak-Token");
        String pol = req.getHeader("X-RuleBreak-Policy");
        if (pol != null) ctx.policy = pol;
        req.setAttribute(ATTR, ctx);
        return true;
    }
}