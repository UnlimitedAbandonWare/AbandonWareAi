package ai.abandonware.nova.orch.failpattern;

/**
 * Minimal set of failure patterns.
 *
 * <p>Keep this enum small (low-cardinality) so Micrometer tagging stays safe.
 */
public enum FailurePatternKind {
    NAVER_TRACE_TIMEOUT,
    CIRCUIT_OPEN,
    DISAMBIG_FALLBACK
}
