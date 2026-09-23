package com.acme.aicore.domain.model;


/**
 * Represents a chat message in the session history.  Messages can be
 * authored by the user or the assistant.  Role is represented as a
 * {@code String} for extensibility; callers should use the factory
 * methods {@link #user(String)} and {@link #assistant(String)} to create
 * messages with the appropriate role.
 */
public record Message(String role, String content) {
    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}