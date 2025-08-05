// src/main/java/com/example/lms/common/ChatSessionScope.java
package com.example.lms.common;

/** ThreadLocal 로 보관하는 ‘현재 chat_session.id’ */
public final class ChatSessionScope {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private ChatSessionScope() {}

    public static void enter(Long id)   { CURRENT.set(id); }
    public static Long  current()       { return CURRENT.get(); }
    public static void clear()          { CURRENT.remove(); }

    /** alias – 컨트롤러에서 가독성을 위해 사용 */
    public static void leave()          { clear(); }
}
