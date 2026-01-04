package com.example.lms.guard.rulebreak;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RuleBreakInterceptor implements HandlerInterceptor {

    private final RuleBreakEvaluator evaluator;

    public RuleBreakInterceptor(RuleBreakEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        RuleBreakContext ctx = null;
        try {
            if (evaluator != null) {
                ctx = evaluator.evaluateFromHeaders(req);
            }
        } catch (Exception ignored) {
            ctx = null;
        }

        if (ctx != null && ctx.isValid()) {
            RuleBreakContextHolder.set(ctx);
        } else {
            RuleBreakContextHolder.clear();
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        RuleBreakContextHolder.clear();
    }
}
