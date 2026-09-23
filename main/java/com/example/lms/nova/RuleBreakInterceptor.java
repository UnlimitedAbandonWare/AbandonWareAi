package com.example.lms.nova;

import com.example.lms.guard.rulebreak.RuleBreakEvaluator;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;




@Component("novaRuleBreakInterceptor")
public class RuleBreakInterceptor implements HandlerInterceptor {
    public static final String HDR = "X-RuleBreak-Token";
    private final RuleBreakEvaluator evaluator;

    public RuleBreakInterceptor(RuleBreakEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        com.example.lms.guard.rulebreak.RuleBreakContext guardContext =
                evaluator == null ? com.example.lms.guard.rulebreak.RuleBreakContext.inactive()
                        : evaluator.evaluateFromHeaders(req);
        if (guardContext != null && guardContext.isValid()) {
            NovaRequestContext.setRuleBreak(toNovaContext(guardContext));
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
    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // ThreadLocal 정리 (스레드 풀 재사용 시 오염 방지)
        NovaRequestContext.clearRuleBreak();
        NovaRequestContext.setBrave(false);
    }

    private static RuleBreakContext toNovaContext(com.example.lms.guard.rulebreak.RuleBreakContext guardContext) {
        RuleBreakContext.Policy policy = switch (guardContext.getPolicy()) {
            case OVERRIDE_DOMAINS -> RuleBreakContext.Policy.ALL_DOMAIN;
            case SPEED_FIRST -> RuleBreakContext.Policy.FAST;
            case SAFE_EXPLORE -> RuleBreakContext.Policy.WIDE;
        };
        return new RuleBreakContext(true, policy, guardContext.getTokenHash(), guardContext.getExpiresAt());
    }
}
