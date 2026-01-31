package com.example.lms.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API-level security exception mapping for method-security failures.
 *
 * <p>Why this exists:</p>
 * <ul>
 *   <li>Exceptions thrown by {@code @PreAuthorize}/{@code @PostAuthorize} happen after the
 *       servlet filter chain. In those cases, {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}
 *       may not run and you can end up with 500/HTML responses.</li>
 *   <li>This advice ensures REST endpoints return consistent JSON with a meaningful 401/403.</li>
 * </ul>
 *
 * <p>Scope:</p>
 * <ul>
 *   <li>Only applies to {@link RestController}-annotated controllers.</li>
 * </ul>
 */
@RestControllerAdvice(annotations = RestController.class)
public class ApiSecurityExceptionAdvice {

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAuthMissing(
            AuthenticationCredentialsNotFoundException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(body(status, "unauthenticated", ex, request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = statusForAccessDenied();
        String code = (status == HttpStatus.UNAUTHORIZED) ? "unauthenticated" : "forbidden";
        return ResponseEntity.status(status).body(body(status, code, ex, request));
    }

    private static HttpStatus statusForAccessDenied() {
        Authentication auth = null;
        try {
            auth = SecurityContextHolder.getContext().getAuthentication();
        } catch (Throwable ignore) {
            // SecurityContext itself may be unavailable in edge cases; treat as unauthenticated
        }

        if (auth == null) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (auth instanceof AnonymousAuthenticationToken) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (!auth.isAuthenticated()) {
            return HttpStatus.UNAUTHORIZED;
        }
        return HttpStatus.FORBIDDEN;
    }

    private static Map<String, Object> body(
            HttpStatus status,
            String code,
            Exception ex,
            HttpServletRequest request
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status.value());
        out.put("error", code);
        out.put("message", ex == null ? null : ex.getMessage());
        if (request != null) {
            out.put("path", request.getRequestURI());
        }
        out.put("timestamp", Instant.now().toString());

        // Propagate trace id if present (best-effort)
        try {
            String trace = MDC.get("traceId");
            if (trace != null && !trace.isBlank()) {
                out.put("trace", trace);
            }
        } catch (Exception ignore) {
        }

        if (ex != null) {
            out.put("exception", ex.getClass().getName());
        }
        return out;
    }
}
