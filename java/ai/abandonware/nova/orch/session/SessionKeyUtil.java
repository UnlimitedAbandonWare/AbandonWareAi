package ai.abandonware.nova.orch.session;

/** Utility helpers for session/breadcrumb identifiers. */
public final class SessionKeyUtil {

    private SessionKeyUtil() {
    }

    /**
     * Build a conversation-level sid.
     *
     * <p>By default this is {@code chat-<sessionId>} to align logs/chunking/history on the same sid.
     */
    public static String conversationSid(String prefix, Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        String p = (prefix == null || prefix.isBlank()) ? "chat-" : prefix;
        return p + sessionId;
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
