package com.example.lms.domain;

/**
 * Enumeration representing the roles a user can have within the system.
 * A {@code USER} represents a standard end‑user with limited privileges
 * while {@code ADMIN} denotes an administrator with elevated rights.
 *
 * <p>This enum is intentionally simple; if additional roles are required
 * they should be added here and propagated through any Spring security
 * configuration.</p>
 */
public enum Role {
    USER,
    ADMIN
}
