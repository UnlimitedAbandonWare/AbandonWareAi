package com.example.lms.service.guard;

import org.springframework.web.context.request.RequestContextHolder;

/**
 * Simple ThreadLocal holder for {@link GuardContext} so that lower layers
 * (search, RAG, memory) can access request-scoped guard metadata without
 * changing every method signature.
 */
public final class GuardContextHolder {

    private static final ThreadLocal<GuardContext> CTX = new ThreadLocal<>();

    private GuardContextHolder() {
    }

    public static void set(GuardContext ctx) {
        CTX.set(ctx);
    }

    public static GuardContext get() {
        return CTX.get();
    }

    /**
     * 오케스트레이션/가드 코드에서 null-branch를 줄이기 위한 헬퍼.
     *
     * <p>
     * ✅ 강화 모드: Web request thread에서만, 비어있는 경우 defaultContext를 1회 ThreadLocal에 설치합니다.
     * - 목적: 상위 레이어가 getOrDefault()를 한 번이라도 호출하면, 하위 레이어의 get()도 null이 되지 않도록.
     * - 범위: Web request thread에서만 set한다. (스케줄러/배치/워커 스레드에선 set하지 않음)
     * </p>
     */
    public static GuardContext getOrDefault() {
        GuardContext ctx = CTX.get();
        if (ctx != null) {
            return ctx;
        }

        GuardContext def = GuardContext.defaultContext();
        if (isWebRequestThread()) {
            // 요청 범위에서만 1회 설치 → 요청 종료 시(필터/컨트롤러 finally) clear로 정리된다.
            CTX.set(def);
        }
        return def;
    }

    private static boolean isWebRequestThread() {
        try {
            return RequestContextHolder.getRequestAttributes() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }


    public static void clear() {
        CTX.remove();
    }
}
