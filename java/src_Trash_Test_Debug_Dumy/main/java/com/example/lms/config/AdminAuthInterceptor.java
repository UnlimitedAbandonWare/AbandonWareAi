// ─────────────────────────────────────────────────────────────────────────────
// 경로: src/main/java/com/example/lms/config/AdminAuthInterceptor.java
// ─────────────────────────────────────────────────────────────────────────────
package com.example.lms.config;

import com.example.lms.service.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminSessionService adminSessionService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        boolean isAdmin = adminSessionService.isValid(req.getCookies());
        req.setAttribute("isAdmin", isAdmin);
        return true; // 항상 흐름은 계속 진행 (권한 체크는 컨트롤러/뷰에서 필요시 사용)
    }
}
