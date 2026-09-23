package com.example.lms.config;

import com.example.lms.security.AdminTokenGuardInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AdminTokenGuardInterceptor} for ops-only admin pages and diagnostics APIs.
 */
@Configuration
@RequiredArgsConstructor
public class AdminTokenGuardWebMvcConfig implements WebMvcConfigurer {

    private final AdminTokenGuardInterceptor adminTokenGuardInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenGuardInterceptor)
                .addPathPatterns(
                        "/admin/**",
                        "/api/admin/fine-tuning/**",
                        "/internal/**",
                        "/internal/nn/**",
                        "/flows/**",
                        "/messages/trigger",
                        "/api/internal/**",
                        "/api/learning/gemini/**",
                        "/api/admin/graph",
                        "/api/admin/graph/**",
                        "/agent/db-context",
                        "/agent/db-context/**",
                        "/api/agent/report",
                        "/api/agent/report/**",
                        "/api/diagnostics/**",
                        "/api/integrations/check",
                        "/api/router",
                        "/api/router/**",
                        "/api/settings",
                        "/api/settings/**",
                        "/model-settings",
                        "/model-settings/**"
                )
                // Run early among MVC interceptors (security filters run before MVC anyway).
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
