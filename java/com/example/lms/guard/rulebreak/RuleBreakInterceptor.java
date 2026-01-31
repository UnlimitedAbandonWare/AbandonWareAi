package com.example.lms.guard.rulebreak;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@ConditionalOnClass(name = {
        "com.example.lms.guard.rulebreak.RuleBreakContext",
        "com.example.lms.guard.rulebreak.RuleBreakContextHolder"
})
public class RuleBreakInterceptor implements HandlerInterceptor {

    private final RuleBreakEvaluator evaluator;
    private final ObjectProvider<DebugEventStore> debugEvents;

    public RuleBreakInterceptor(RuleBreakEvaluator evaluator, ObjectProvider<DebugEventStore> debugEvents) {
        this.evaluator = evaluator;
        this.debugEvents = debugEvents;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        RuleBreakContext ctx = null;
        try {
            if (evaluator != null) {
                ctx = evaluator.evaluateFromHeaders(req);
            }
        } catch (Throwable t) {
            ctx = null;
            DebugEventStore s = debugEvents.getIfAvailable();
            if (s != null) {
                s.emit(
                        DebugProbeType.RULE_BREAK,
                        DebugEventLevel.ERROR,
                        "rulebreak.preHandle.failed",
                        "RuleBreakInterceptor preHandle failed (fail-soft)",
                        "RuleBreakInterceptor",
                        java.util.Map.of(
                                "uri", req.getRequestURI(),
                                "method", req.getMethod(),
                                "remote", req.getRemoteAddr()
                        ),
                        t
                );
            }
        }

        try {
            if (ctx != null && ctx.isValid()) {
                RuleBreakContextHolder.set(ctx);
            } else {
                RuleBreakContextHolder.clear();
            }
        } catch (Throwable t) {
            // Fail-soft: missing/relocated classes must never break the whole request.
            DebugEventStore s = debugEvents.getIfAvailable();
            if (s != null) {
                try {
                    s.emit(
                            DebugProbeType.RULE_BREAK,
                            DebugEventLevel.WARN,
                            "rulebreak.context.holder.unavailable",
                            "RuleBreakContextHolder unavailable; skipping rule-break context propagation",
                            "RuleBreakInterceptor",
                            java.util.Map.of(
                                    "uri", req.getRequestURI(),
                                    "method", req.getMethod(),
                                    "remote", req.getRemoteAddr(),
                                    "thread", Thread.currentThread().getName()),
                            t);
                } catch (Throwable ignore) {
                }
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        try {
            RuleBreakContextHolder.clear();
        } catch (Throwable t) {
            DebugEventStore s = debugEvents.getIfAvailable();
            if (s != null) {
                s.emit(
                        DebugProbeType.RULE_BREAK,
                        DebugEventLevel.WARN,
                        "rulebreak.afterCompletion.clear.failed",
                        "RuleBreakContextHolder.clear failed",
                        "RuleBreakInterceptor",
                        java.util.Map.of(
                                "uri", req.getRequestURI(),
                                "method", req.getMethod()
                        ),
                        t
                );
            }
        }
    }
}
