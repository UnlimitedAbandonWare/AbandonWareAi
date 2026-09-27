package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

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

    private static final System.Logger LOG = System.getLogger(ApiSecurityExceptionAdvice.class.getName());

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

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(
            AsyncRequestNotUsableException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        TraceStore.put("api.clientDisconnect", true);
        TraceStore.put("api.clientDisconnect.reason", "async_request_not_usable");
        if (request != null) {
            String path = request.getRequestURI();
            TraceStore.put("api.clientDisconnect.pathHash", SafeRedactor.hashValue(path));
            TraceStore.put("api.clientDisconnect.pathLength", path == null ? 0 : path.length());
        }
        TraceStore.put("api.clientDisconnect.errorType", errorType(ex));
        TraceStore.put("api.clientDisconnect.errorHash", SafeRedactor.hashValue(String.valueOf(ex)));
        LOG.log(System.Logger.Level.DEBUG,
                "API client disconnected stage=async_request_not_usable errorType=" + errorType(ex));
        try {
            if (response != null && !response.isCommitted()) {
                response.setStatus(HttpStatus.NO_CONTENT.value());
            }
        } catch (RuntimeException statusError) {
            traceSuppressed("client_disconnect_status", statusError);
            LOG.log(System.Logger.Level.DEBUG,
                    "API client disconnect status skipped errorType=" + errorType(statusError));
        }
    }

    private static HttpStatus statusForAccessDenied() {
        Authentication auth = null;
        try {
            auth = SecurityContextHolder.getContext().getAuthentication();
        } catch (Throwable ignore) {
            traceSuppressed("auth_lookup", ignore);
            LOG.log(System.Logger.Level.DEBUG,
                    "API security auth lookup skipped errorType=" + errorType(ignore));
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
        out.put("message", ex == null ? null : com.example.lms.trace.SafeRedactor.traceLabelOrFallback(ex.getMessage(), ""));
        if (request != null) {
            String path = request.getRequestURI();
            out.put("pathHash", com.example.lms.trace.SafeRedactor.hashValue(path));
            out.put("pathLength", path == null ? 0 : path.length());
        }
        out.put("timestamp", Instant.now().toString());

        // Propagate trace id if present (best-effort)
        try {
            String trace = MDC.get("traceId");
            if (trace != null && !trace.isBlank()) {
                out.put("traceHash", com.example.lms.trace.SafeRedactor.hashValue(trace));
            }
        } catch (Exception traceError) {
            traceSuppressed("trace_id", traceError);
            LOG.log(System.Logger.Level.DEBUG,
                    "API security error body skipped stage=trace_id errorType="
                            + errorType(traceError));
        }

        return out;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("api.security.suppressed.stage", safeStage);
        TraceStore.put("api.security.suppressed.errorType", errorType(failure));
        TraceStore.put("api.security.suppressed." + safeStage, true);
        TraceStore.put("api.security.suppressed." + safeStage + ".errorType", errorType(failure));
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
