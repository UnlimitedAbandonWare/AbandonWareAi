package ai.abandonware.nova.orch.failpattern;

/**
 * Minimal set of failure patterns.
 *
 * <p>Keep this enum small (low-cardinality) so Micrometer tagging stays safe.
 */
public enum FailurePatternKind {
    NAVER_TRACE_TIMEOUT,
    CIRCUIT_OPEN,
    DISAMBIG_FALLBACK,
    SEARCH_ZERO_RESULT,
    SEARCH_AFTER_FILTER_STARVATION,
    EVIDENCE_INSUFFICIENT,
    WEB_STARVATION
}
