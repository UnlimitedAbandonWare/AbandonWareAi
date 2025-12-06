package com.example.lms.web;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
@Component
public class RuleBreakInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-RuleBreak-Token";
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tok = request.getHeader(HEADER);
        if (tok != null && !tok.isBlank()) {
            request.setAttribute("ruleBreak.token", tok);
        }
        return true;
    }
}