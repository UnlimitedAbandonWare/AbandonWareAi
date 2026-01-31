package com.example.lms.debug;

/**
 * A compact, JSON-friendly representation of an error/exception.
 */
public record DebugError(
        String type,
        String message,
        String stack
) {
}
