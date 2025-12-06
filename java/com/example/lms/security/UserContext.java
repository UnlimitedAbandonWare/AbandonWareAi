package com.example.lms.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;



/**
 * Utility class for retrieving the current authenticated user identifier.
 * This class attempts to extract a username from the Spring Security
 * authentication context without throwing.  When no authentication
 * information is available or the principal cannot be interpreted as
 * a username, {@link Optional#empty()} is returned.  Callers should
 * gracefully handle the absence of a user id by falling back to
 * application specific defaults (e.g. "anonymous").
 */
public final class UserContext {
    private UserContext() { }

    /**
     * Determine the current user identifier from the Spring Security
     * context.  When an {@link org.springframework.security.core.userdetails.UserDetails}
     * principal is present, the username is returned.  If the principal
     * is a simple string, it is returned as is.  Any other principal
     * types result in an empty optional.
     *
     * @return an optional containing the current username or empty
     */
    public static Optional<String> currentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return Optional.empty();
            }
            Object principal = auth.getPrincipal();
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
                return Optional.ofNullable(ud.getUsername());
            }
            if (principal instanceof String s) {
                return Optional.ofNullable(s);
            }
        } catch (Exception ignore) {
            // ignore and return empty
        }
        return Optional.empty();
    }
}