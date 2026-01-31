package com.abandonwareai.zerobreak.interceptor;

import com.abandonwareai.zerobreak.context.ZeroBreakContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * Reads Zero Break headers and attaches ZeroBreakContext to the request.
 * This interceptor is non-intrusive and safe to register in any Spring MVC app.
 */
public class RuleBreakInterceptor implements HandlerInterceptor {
    public static final String ATTR_KEY = "ZB_CTX";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String planId = firstNonEmpty(request.getHeader("X-Plan-Id"), "safe_autorun.v1");
        String token = request.getHeader("X-ZeroBreak-Token"); // signed token (validation pluggable)
        String policy = request.getHeader("X-ZeroBreak-Policy"); // recency|max_recall|speed_first|wide_web

        ZeroBreakContext ctx = new ZeroBreakContext();
        ctx.setPlanId(planId);
        if (token != null && !token.isBlank()) {
            ctx.setZeroBreakEnabled(true);
        }
        if (policy != null && !policy.isBlank()) {
            List<String> pols = Arrays.stream(policy.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            ctx.setPolicies(pols);
        }
        // Banner is optional, can be set by config
        ctx.setBannerText("【주의: 확장 탐색(Zero Break) 모드 적용】");

        request.setAttribute(ATTR_KEY, ctx);
        return true;
    }

    @Nullable
    private static String firstNonEmpty(String a, String fallback) {
        return (a != null && !a.isBlank()) ? a : fallback;
    }
}