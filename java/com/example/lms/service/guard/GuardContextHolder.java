package com.example.lms.service.guard;

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

    public static void clear() {
        CTX.remove();
    }
}
