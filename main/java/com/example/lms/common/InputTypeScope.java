// src/main/java/com/example/lms/common/InputTypeScope.java
package com.example.lms.common;


/**
 * Thread local holder for the current input type.  When a chat request
 * originates from a voice input on the client, the front-end sets
 * ChatRequestDto.inputType="voice".  This scope allows downstream
 * services such as CognitiveStateExtractor to detect that the
 * current query was dictated rather than typed.  The value is
 * automatically cleared after the request completes via ChatService.
 */
public final class InputTypeScope {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private InputTypeScope() {}

    /**
     * Set the current input type for the ongoing request.  Passing
     * {@code null} clears the current value.
     *
     * @param type the input type, e.g. "voice" or "text"
     */
    public static void enter(String type) {
        if (type == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(type);
        }
    }

    /**
     * Return the current input type associated with this thread.  May
     * return {@code null} if no input type has been set.
     *
     * @return the input type or null
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Remove the current input type from the thread local storage.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Alias for {@link #clear()} to improve readability when leaving
     * a request context.
     */
    public static void leave() {
        clear();
    }
}