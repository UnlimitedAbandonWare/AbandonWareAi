package com.example.lms.health;

import com.example.lms.service.rag.QueryComplexityClassifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;



/**
 * Health indicator for the query complexity classifier.  When the
 * classifier bean is present in the application context and it is
 * configured via {@code ml.query-classifier.enabled=true}, this indicator
 * reports {@code UP}.  When no classifier bean is registered the
 * indicator returns {@code DOWN}.  This simplistic check does not
 * introspect the underlying model state but provides a high level
 * indication of whether the classifier is active.
 */
@Component
public class ClassifierHealthIndicator implements HealthIndicator {

    private final QueryComplexityClassifier classifier;

    @Autowired
    public ClassifierHealthIndicator(@Autowired(required = false) QueryComplexityClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public Health health() {
        if (classifier == null) {
            return Health.down().withDetail("classifier", "none").build();
        }
        return Health.up().withDetail("classifier", classifier.getClass().getSimpleName()).build();
    }
}