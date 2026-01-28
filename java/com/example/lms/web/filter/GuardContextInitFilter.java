package com.example.lms.web.filter;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * ✅ UAW 미구현 축 보강:
 * <ul>
 *   <li>GuardContextHolder가 비어있는 요청 경로에서도 기본 GuardContext를 주입해 NPE 방지</li>
 *   <li>x-request-id / sessionId MDC 상관관계 키로 로그 추적성 확보</li>
 * </ul>
 *
 * <p>
 * 주의: ThreadLocal 누수(스레드풀 재사용 시 컨텍스트 오염)를 방지하기 위해,
 * 요청 시작 시/종료 시 모두 GuardContextHolder를 정리(clear)합니다.
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnClass(name = "com.example.lms.service.guard.GuardContextHolder")
public class GuardContextInitFilter extends OncePerRequestFilter {

    private final ObjectProvider<DebugEventStore> debugEvents;

    public GuardContextInitFilter(ObjectProvider<DebugEventStore> debugEvents) {
        this.debugEvents = debugEvents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1) request correlation id (없으면 생성)
        String rid = firstNonBlank(
                request.getHeader("x-request-id"),
                request.getHeader("X-Request-Id"),
                request.getHeader("x-correlation-id"),
                request.getHeader("X-Correlation-Id")
        );
        if (!StringUtils.hasText(rid)) {
            rid = UUID.randomUUID().toString();
        }

        // 2) MDC 주입 (기존 값 있으면 덮어쓰지 않음)
        boolean putRid = false;
        if (!StringUtils.hasText(MDC.get("x-request-id"))) {
            MDC.put("x-request-id", rid);
            putRid = true;
        }
        boolean putSession = false;
        if (!StringUtils.hasText(MDC.get("sessionId"))) {
            // Prefer a real session id header when present; otherwise fall back to request correlation id.
            String sid = firstNonBlank(
                    request.getHeader("X-Session-Id"),
                    request.getHeader("x-session-id"),
                    request.getHeader("X-SessionId"),
                    request.getHeader("x-sessionid")
            );
            MDC.put("sessionId", StringUtils.hasText(sid) ? sid : rid);
            putSession = true;
        }

        // 3) GuardContext 요청 경계 초기화 (ThreadLocal 누수 방지)
        // 만약 요청 시작 시점에 이미 값이 남아있다면(스레드풀 재사용), 이전 요청이 유출된 상태다.
        boolean guardEnabled = true;
        try {
            GuardContext existing = GuardContextHolder.get();
            if (existing != null) {
                GuardContextHolder.clear();
                DebugEventStore s = debugEvents.getIfAvailable();
                if (s != null) {
                    s.emit(
                            DebugProbeType.GUARD_CONTEXT,
                            DebugEventLevel.WARN,
                            "guardContext.leak",
                            "GuardContext leak detected; cleared previous context",
                            "GuardContextInitFilter",
                            java.util.Map.of(
                                    "uri", request.getRequestURI(),
                                    "method", request.getMethod(),
                                    "thread", Thread.currentThread().getName()
                            ),
                            null
                    );
                }
            }
            GuardContextHolder.set(GuardContext.defaultContext());
        } catch (Throwable t) {
            // Fail-soft: do not 500 the request path due to missing guard classes.
            guardEnabled = false;
            DebugEventStore s = debugEvents.getIfAvailable();
            if (s != null) {
                s.emit(
                        DebugProbeType.GUARD_CONTEXT,
                        DebugEventLevel.ERROR,
                        "guardContext.init.failed",
                        "GuardContext init skipped (fail-soft) due to runtime error",
                        "GuardContextInitFilter",
                        java.util.Map.of(
                                "uri", request.getRequestURI(),
                                "method", request.getMethod(),
                                "rid", rid
                        ),
                        t
                );
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 요청 종료 시 무조건 clear (created 여부와 무관)
            if (guardEnabled) {
                try {
                    GuardContextHolder.clear();
                } catch (Throwable t) {
                    DebugEventStore s = debugEvents.getIfAvailable();
                    if (s != null) {
                        s.emit(
                                DebugProbeType.GUARD_CONTEXT,
                                DebugEventLevel.WARN,
                                "guardContext.clear.failed",
                                "GuardContext clear failed (possible classpath issue)",
                                "GuardContextInitFilter",
                                java.util.Map.of(
                                        "uri", request.getRequestURI(),
                                        "method", request.getMethod()
                                ),
                                t
                        );
                    }
                }
            }
            if (putSession) {
                MDC.remove("sessionId");
            }
            if (putRid) {
                MDC.remove("x-request-id");
            }
        }
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) {
            return null;
        }
        for (String x : xs) {
            if (StringUtils.hasText(x)) {
                return x;
            }
        }
        return null;
    }
}
