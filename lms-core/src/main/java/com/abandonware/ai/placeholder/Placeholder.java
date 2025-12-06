package com.abandonware.ai.placeholder;

/**
 * Minimal placeholder to allow :lms-core to compile without pulling in incomplete sources.
 * This class is intentionally tiny and has no external dependencies.
 */
public final class Placeholder {
    private Placeholder() {}
    public static String ping() { return "ok"; }
}