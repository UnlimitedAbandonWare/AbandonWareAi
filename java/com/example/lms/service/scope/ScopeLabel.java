package com.example.lms.service.scope;

/**
 * Simple scope label inferred from a text + its metadata.
 *
 * <p>Used to reduce "part/whole" contamination in vector memory.
 * This is intentionally lightweight and heuristic-based (fail-soft).</p>
 */
public record ScopeLabel(
        String anchorKey,
        String kind,          // WHOLE | PART
        String partKey,       // PART only
        double confidence,    // 0~1
        String reason
) {
}
