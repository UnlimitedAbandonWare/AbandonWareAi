package com.example.lms.common;

/**
 * Thread-local holder for the current chat session id.
 */
public final class ChatSessionScope {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private ChatSessionScope() {
    }

    public static void enter(Long sessionId) {
        if (sessionId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(sessionId);
        }
    }

    public static Long current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void leave() {
        clear();
    }
}
