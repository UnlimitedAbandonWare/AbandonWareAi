package com.example.lms.service.rag;

import com.example.lms.assist.JevChoiceAdvisor.ChoiceObservation;
import java.util.Objects;

/** Request-only adapter; the existing classifier remains the sole Spring bean. */
public final class JevComplexityClassifier implements QueryComplexityClassifier {
    private final QueryComplexityClassifier delegate;
    public JevComplexityClassifier(QueryComplexityClassifier delegate) { this.delegate=Objects.requireNonNull(delegate); }
    @Override public QueryComplexityGate.Level classify(String query) { return delegate.classify(query); }
    public QueryComplexityGate.Level classify(String query, ChoiceObservation observation) {
        return acceptedLevel(observation).orElseGet(() -> classify(query));
    }
    /** Acceptance is produced by the scoped runtime, never by query metadata. */
    public static java.util.Optional<QueryComplexityGate.Level> acceptedLevel(ChoiceObservation observation) {
        if (observation == null || !observation.schemaValid() || !observation.confidenceAccepted()
                || observation.probability() == null || observation.probability().isEmpty())
            return java.util.Optional.empty();
        double probability = observation.probability().getAsDouble();
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) return java.util.Optional.empty();
        try { return java.util.Optional.of(QueryComplexityGate.Level.valueOf(observation.choice())); }
        catch (RuntimeException malformed) { return java.util.Optional.empty(); }
    }
}
