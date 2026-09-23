package com.example.lms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.List;

/**
 * Filter-level guard for settings endpoints that may be rejected before MVC
 * interceptors run, such as method mismatches, case variants, and preflight.
 */
public class AdminTokenGuardFilter extends OncePerRequestFilter {

    private final AdminTokenGuardInterceptor interceptor;

    public AdminTokenGuardFilter(AdminTokenGuardInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return !(path.equals("/api/settings")
                || path.startsWith("/api/settings/")
                || path.equals("/admin")
                || path.startsWith("/admin/")
                || path.equals("/api/admin")
                || path.startsWith("/api/admin/")
                || isAdminGraphPath(path)
                || path.equals("/api/admin/fine-tuning")
                || path.startsWith("/api/admin/fine-tuning/")
                || path.equals("/dashboard")
                || path.startsWith("/dashboard/")
                || path.equals("/api/agent/report")
                || path.startsWith("/api/agent/report/")
                || path.equals("/api/router")
                || path.startsWith("/api/router/")
                || path.equals("/agent/db-context")
                || path.startsWith("/agent/db-context/")
                || path.equals("/model-settings")
                || path.startsWith("/model-settings/")
                || path.equals("/internal/agent")
                || path.startsWith("/internal/agent/")
                || path.equals("/internal/soak")
                || path.startsWith("/internal/soak/")
                || path.equals("/internal/autoevolve")
                || path.startsWith("/internal/autoevolve/")
                || path.equals("/internal/nn")
                || path.startsWith("/internal/nn/")
                || path.equals("/api/internal")
                || path.startsWith("/api/internal/")
                || path.equals("/api/learning/gemini")
                || path.startsWith("/api/learning/gemini/")
                || path.equals("/api/integrations/check")
                || isDiagnosticRequest(path)
                || path.equals("/v1/tasks") || path.startsWith("/v1/tasks/")
                || isOperationalWriteRequest(request, path)
                );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (!interceptor.preHandle(request, response, this)) {
                return;
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
        if (interceptor.isPresentedTokenAuthorized(request)) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "admin-token",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    private static String normalizedPath(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isBlank() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri.toLowerCase(Locale.ROOT);
    }

    private static boolean isDiagnosticRequest(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/diagnostics") || path.startsWith("/api/diagnostics/");
    }

    static boolean isAdminGraphPath(String path) {
        return path != null
                && (path.equals("/api/admin/graph") || path.startsWith("/api/admin/graph/"));
    }

    private static boolean isOperationalWriteRequest(HttpServletRequest request, String path) {
        if (request == null || path == null) {
            return false;
        }
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return path.equals("/v1/tasks") || path.startsWith("/v1/tasks/")
                || path.equals("/flows") || path.startsWith("/flows/")
                || path.equals("/api/rag/probe")
                || path.equals("/api/nova/outbox") || path.startsWith("/api/nova/outbox/")
                || path.equals("/api/train") || path.startsWith("/api/train/")
                || path.equals("/api/translate/train") || path.equals("/api/translate/train-now")
                || path.equals("/webhooks/channel")
                || path.equals("/messages/trigger");
    }
}
