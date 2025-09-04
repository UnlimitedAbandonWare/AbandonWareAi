package com.example.risk;

import com.example.lms.ml.SoftmaxClassifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a lazily-created {@link SoftmaxClassifier} for risk scoring.
 * The classifier is stored in an atomic reference to allow safe
 * concurrent initialisation.  When training occurs the classifier
 * will be initialised with a feature dimensionality determined by
 * the first training batch.
 */
@Component
public class RiskModelProvider {
    private final AtomicReference<SoftmaxClassifier> ref = new AtomicReference<>();

    /**
     * Get the current classifier or null if none has been initialised.
     */
    public SoftmaxClassifier get() {
        return ref.get();
    }

    /**
     * Check if a classifier has been initialised.
     */
    public boolean isReady() {
        return ref.get() != null;
    }

    /**
     * Ensure that a classifier exists with the given feature dimension.
     * If a classifier is already present it is returned; otherwise a new
     * classifier is created with default hyperparameters.  The creation
     * is atomic so that concurrent calls do not create multiple instances.
     */
    public SoftmaxClassifier ensure(int featureDim) {
        SoftmaxClassifier cur = ref.get();
        if (cur != null) {
            return cur;
        }
        SoftmaxClassifier created = new SoftmaxClassifier(featureDim, 2, 42L, 1e-2, 1e-4, 0.05);
        ref.compareAndSet(null, created);
        return ref.get();
    }

    /**
     * Replace the current classifier with a new model.  Mainly used by tests.
     */
    public void set(SoftmaxClassifier model) {
        ref.set(model);
    }
}