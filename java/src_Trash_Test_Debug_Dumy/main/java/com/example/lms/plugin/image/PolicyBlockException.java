package com.example.lms.plugin.image;

/**
 * Thrown when a prompt is deemed likely to violate content policy (e.g. direct
 * references to copyrighted characters). Caught by the controller and mapped
 * to a POLICY_BLOCK reason.
 */
public class PolicyBlockException extends RuntimeException {
    public PolicyBlockException(String message) { super(message); }
    public PolicyBlockException(String message, Throwable cause) { super(message, cause); }
}