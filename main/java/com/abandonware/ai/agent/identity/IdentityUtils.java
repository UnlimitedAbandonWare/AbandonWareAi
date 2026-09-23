package com.abandonware.ai.agent.identity;


/** Identity helper that maps the current request context into a logical identity string. */
public final class IdentityUtils {
    /** Returns identity string: "user:<userId>" if provided, otherwise "guest:<sessionId>". */
    public static String identityOf(String userIdOrNull, String sessionId) {
        if (userIdOrNull != null && !userIdOrNull.isBlank()) {
            return "user:" + userIdOrNull.trim();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return "guest:" + sessionId.trim();
    }

    /** Extract owner type from identity string. */
    public static String ownerType(String identity) {
        if (identity == null) return "GUEST";
        return identity.startsWith("user:") ? "USER" : "GUEST";
    }

    private IdentityUtils() {}
}
