package com.example.lms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Token auth filter for /api/admin/vector/** endpoints.
 *
 * <p>
 * Header options:
 * <ul>
 * <li>Authorization: Bearer &lt;token&gt;</li>
 * <li>X-Vector-Admin-Token: &lt;token&gt;</li>
 * </ul>
 * </p>
 */
public class VectorAdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER_TOKEN = "X-Vector-Admin-Token";

    private final String token;

    public VectorAdminTokenFilter(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /**
     * This filter is intended only for {@code /api/admin/vector/**} endpoints.
     *
     * <p>
     * In Spring Boot, any {@link jakarta.servlet.Filter} that becomes a bean can be
     * auto-registered into the servlet filter chain and end up executing for ALL
     * requests (including the home page {@code /}).
     * </p>
     *
     * <p>
     * We therefore defensively skip non-vector-admin paths here as an additional
     * guardrail (even though we also disable the servlet auto-registration via
     * {@code FilterRegistrationBean#setEnabled(false)} in configuration).
     * </p>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return true;
        }

        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isBlank() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }

        // Match both "/api/admin/vector" and any sub-path.
        return !(uri.equals("/api/admin/vector") || uri.startsWith("/api/admin/vector/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Fail-closed if token not configured.
        if (token.isBlank()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getOutputStream()
                    .write("{\"error\":\"vector.admin.token not configured\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String presented = extractToken(request);
        if (!constantTimeEquals(token, presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getOutputStream().write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                "vector-admin",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_VECTOR_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        if (request == null)
            return "";
        String h = request.getHeader(HEADER_TOKEN);
        if (h != null && !h.isBlank())
            return h.trim();

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null) {
            String a = auth.trim();
            if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return a.substring(7).trim();
            }
        }
        return "";
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length)
            return false;
        int r = 0;
        for (int i = 0; i < aa.length; i++) {
            r |= aa[i] ^ bb[i];
        }
        return r == 0;
    }
}
