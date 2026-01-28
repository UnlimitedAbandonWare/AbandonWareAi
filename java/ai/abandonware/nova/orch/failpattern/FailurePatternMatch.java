package ai.abandonware.nova.orch.failpattern;

/**
 * A detected failure-pattern with a canonical "source" and an optional key/detail.
 *
 * @param kind   failure kind
 * @param source canonical source id (e.g., web/vector/kg/disambig)
 * @param key    optional detail (e.g., breaker key)
 */
public record FailurePatternMatch(
        FailurePatternKind kind,
        String source,
        String key
) {
}
