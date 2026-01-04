package com.example.lms.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 흔한 자동 스캐너/취약점 탐지 요청을 초기에 404로 short-circuit.
 *
 * <p>
 * ERROR_AWX.txt에서 관측된 .env/wp/phpinfo 류 트래픽이 Spring Security/CSRF까지
 * 타며 CPU/로그를 낭비하는 문제를 줄이기 위한 필터.
 * </p>
 *
 * 기본 활성: security.block-scanner-paths.enabled=true
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SuspiciousPathBlockFilter extends OncePerRequestFilter {

    @Value("${security.block-scanner-paths.enabled:true}")
    private boolean enabled;

    private static final List<String> PREFIX_BLOCKLIST = List.of(
            "/.env", "/.aws", "/.git", "/wp-", "/wordpress",
            "/phpinfo", "/info.php", "/cgi-bin", "/vendor/phpunit",
            "/server-status", "/actuator/env"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String qs = request.getQueryString();

        if (isBlocked(uri, qs)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isBlocked(String uri, String qs) {
        if (uri == null) return false;
        String u = uri.toLowerCase();
        for (String p : PREFIX_BLOCKLIST) {
            if (u.startsWith(p)) return true;
        }
        if (qs != null) {
            String q = qs.toLowerCase();
            if (q.contains("cmd=") && (q.contains("wget") || q.contains("curl"))) {
                return true;
            }
        }
        return false;
    }
}
